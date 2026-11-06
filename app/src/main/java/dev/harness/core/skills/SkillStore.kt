package dev.harness.core.skills

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** One Agent Skill: a folder holding SKILL.md (YAML front matter + instructions) and any helper files. */
data class Skill(
    val name: String,
    val description: String,
    val dir: File,
    val enabled: Boolean,
    /** Folder the skill was found under, relative to the workspace (".farai/skills" or ".claude/skills"). */
    val source: String,
) {
    val file: File get() = File(dir, SkillStore.SKILL_FILE)
}

/**
 * Agent Skills in the SKILL.md format, stored inside the workspace so the read and
 * bash tools (and Linux) can reach every helper file:
 *
 *   <workspace>/.farai/skills/<name>/SKILL.md   (FARAI's own folder, where /skills new and import write)
 *   <workspace>/.claude/skills/<name>/SKILL.md  (read too, for compatibility)
 *
 * Only each skill's name and description go into the system prompt; the model loads the full
 * instructions with the `skill` tool when a task matches (progressive disclosure).
 * A skill is switched off by an empty `.disabled` file in its folder.
 */
class SkillStore(private val workspaceRoot: File) {
    val home: File get() = File(workspaceRoot, PRIMARY)

    private val roots: List<Pair<String, File>> get() = listOf(PRIMARY to File(workspaceRoot, PRIMARY), CLAUDE to File(workspaceRoot, CLAUDE))

    fun list(): List<Skill> {
        val seen = HashSet<String>()
        val out = ArrayList<Skill>()
        for ((label, root) in roots) {
            val dirs = root.listFiles()?.filter { it.isDirectory && File(it, SKILL_FILE).isFile }?.sortedBy { it.name.lowercase() } ?: continue
            for (d in dirs) {
                val s = runCatching { parse(d, label) }.getOrNull() ?: continue
                if (seen.add(s.name.lowercase())) out += s
            }
        }
        return out
    }

    fun enabled(): List<Skill> = list().filter { it.enabled }

    fun find(name: String): Skill? {
        val n = name.trim().removePrefix("/").lowercase()
        return list().firstOrNull { it.name.lowercase() == n || it.dir.name.lowercase() == n }
    }

    /** SKILL.md without its front matter. */
    fun body(skill: Skill): String = splitFrontMatter(skill.file.readText()).second.trim()

    /** Helper files in the skill folder (relative paths), SKILL.md excluded. */
    fun files(skill: Skill, max: Int = 60): List<String> =
        skill.dir.walkTopDown().filter { it.isFile && it.name != SKILL_FILE && it.name != DISABLED }
            .map { it.relativeTo(skill.dir).invariantSeparatorsPath }.sorted().take(max).toList()

    /** Reads a helper file, refusing paths that leave the skill folder. */
    fun readFile(skill: Skill, relative: String): String {
        val f = File(skill.dir, relative).canonicalFile
        if (!f.path.startsWith(skill.dir.canonicalPath + File.separator)) throw IOException("'$relative' is outside the skill folder")
        if (!f.isFile) throw IOException("no file '$relative' in skill ${skill.name}")
        if (f.length() > 512 * 1024) throw IOException("'$relative' is too large to load (${f.length()} bytes); use the read tool with offset/limit")
        return f.readText()
    }

    fun create(name: String, description: String): Skill {
        val slug = slug(name)
        require(slug.isNotEmpty()) { "skill name must contain letters or digits" }
        val dir = File(home, slug)
        if (File(dir, SKILL_FILE).exists()) throw IOException("skill '$slug' already exists")
        dir.mkdirs()
        File(dir, SKILL_FILE).writeText(template(slug, description.ifBlank { "TODO: say what this skill does and when to use it." }))
        return parse(dir, PRIMARY)
    }

    /**
     * Imports a skill from a folder containing SKILL.md, a single .md file, or a .zip (one skill
     * at its root or in a single top folder; a zip holding several skill folders imports them all).
     */
    fun import(src: File, overwrite: Boolean = false): List<Skill> {
        if (!src.exists()) throw IOException("not found: ${src.path}")
        home.mkdirs()
        return when {
            src.isDirectory -> {
                val skillDirs = if (File(src, SKILL_FILE).isFile) listOf(src)
                else src.listFiles()?.filter { File(it, SKILL_FILE).isFile }.orEmpty()
                if (skillDirs.isEmpty()) throw IOException("no SKILL.md in ${src.name}")
                skillDirs.map { d -> install(d.name, overwrite) { target -> d.copyRecursively(target, overwrite = true) } }
            }
            src.name.endsWith(".zip", true) -> importZip(src, overwrite)
            src.name.endsWith(".md", true) -> {
                val text = src.readText()
                val name = frontMatter(text)["name"] ?: src.nameWithoutExtension.takeUnless { it.equals("SKILL", true) } ?: src.parentFile?.name ?: "skill"
                listOf(install(name, overwrite) { target -> File(target, SKILL_FILE).writeText(text) })
            }
            else -> throw IOException("import a folder with SKILL.md, a .md file or a .zip")
        }
    }

    private fun importZip(zip: File, overwrite: Boolean): List<Skill> {
        val staging = File(home, ".import-${System.currentTimeMillis()}")
        try {
            staging.mkdirs()
            val stagingPath = staging.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.name.startsWith("__MACOSX")) continue
                    val out = File(staging, e.name).canonicalFile
                    if (!out.path.startsWith(stagingPath)) throw IOException("unsafe path in zip: ${e.name}")
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                }
            }
            val found = staging.walkTopDown().filter { it.isFile && it.name == SKILL_FILE }.map { it.parentFile!! }.toList()
            if (found.isEmpty()) throw IOException("no SKILL.md inside ${zip.name}")
            return found.map { d ->
                val name = if (d == staging) zip.nameWithoutExtension else d.name
                install(name, overwrite) { target -> d.copyRecursively(target, overwrite = true) }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun install(name: String, overwrite: Boolean, fill: (File) -> Unit): Skill {
        val target = File(home, slug(name).ifEmpty { "skill" })
        if (target.exists()) {
            if (!overwrite) throw IOException("skill '${target.name}' already exists (use /skills import <path> --force to replace it)")
            target.deleteRecursively()
        }
        target.mkdirs()
        fill(target)
        return parse(target, PRIMARY)
    }

    fun remove(skill: Skill) { skill.dir.deleteRecursively() }

    fun setEnabled(skill: Skill, on: Boolean) {
        val marker = File(skill.dir, DISABLED)
        if (on) marker.delete() else marker.writeText("")
    }

    /** Puts the bundled starter skills in place the first time (never overwrites the user's copy). */
    fun seed(defaults: Map<String, String>) {
        val stamp = File(home, ".seeded")
        if (stamp.exists()) return
        for ((name, text) in defaults) {
            val f = File(home, "$name/$SKILL_FILE")
            if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(text) }
        }
        home.mkdirs()
        stamp.writeText("1")
    }

    private fun parse(dir: File, source: String): Skill {
        val text = File(dir, SKILL_FILE).readText()
        val fm = frontMatter(text)
        val description = fm["description"]?.takeIf { it.isNotBlank() }
            ?: splitFrontMatter(text).second.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: ""
        return Skill(
            name = fm["name"]?.takeIf { it.isNotBlank() } ?: dir.name,
            description = description.take(1024),
            dir = dir,
            enabled = !File(dir, DISABLED).exists(),
            source = source,
        )
    }

    companion object {
        const val SKILL_FILE = "SKILL.md"
        const val DISABLED = ".disabled"
        const val PRIMARY = ".farai/skills"
        const val CLAUDE = ".claude/skills"

        fun slug(name: String): String =
            name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)

        /** Splits "---\nyaml\n---\nbody" into (yaml, body). No front matter → ("", text). */
        fun splitFrontMatter(text: String): Pair<String, String> {
            val t = text.removePrefix("\uFEFF")
            if (!t.startsWith("---")) return "" to t
            val lines = t.lines()
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end < 0) return "" to t
            return lines.subList(1, end + 1).joinToString("\n") to lines.drop(end + 2).joinToString("\n")
        }

        /**
         * A small YAML reader for the flat keys skills use: `key: value`, quoted values, and
         * folded/literal blocks (`key: >` or `key: |` followed by indented lines).
         */
        fun frontMatter(text: String): Map<String, String> {
            val yaml = splitFrontMatter(text).first
            if (yaml.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            val lines = yaml.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val m = Regex("""^([A-Za-z0-9_-]+)\s*:\s*(.*)$""").find(line)
                if (m == null || line.startsWith(" ") || line.startsWith("\t")) { i++; continue }
                val key = m.groupValues[1].lowercase()
                var value = m.groupValues[2].trim()
                if (value == ">" || value == "|" || value == ">-" || value == "|-" || value.isEmpty()) {
                    val block = ArrayList<String>()
                    var j = i + 1
                    while (j < lines.size && (lines[j].startsWith(" ") || lines[j].startsWith("\t") || lines[j].isBlank())) { block += lines[j].trim(); j++ }
                    value = if (value.startsWith("|")) block.joinToString("\n").trim() else block.filter { it.isNotEmpty() }.joinToString(" ")
                    i = j
                } else i++
                if ((value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) || (value.startsWith("'") && value.endsWith("'") && value.length >= 2)) {
                    value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("''", "'")
                }
                out[key] = value
            }
            return out
        }

        fun template(name: String, description: String): String = """
            |---
            |name: $name
            |description: $description
            |---
            |
            |# $name
            |
            |## When to use
            |Describe the tasks or requests that should trigger this skill.
            |
            |## Instructions
            |1. Step-by-step instructions the agent follows.
            |2. Reference helper files in this folder by relative path, e.g. `scripts/run.py` or `reference.md`.
            |
            |## Examples
            |- Example request and what a good result looks like.
            |""".trimMargin()
    }
}
