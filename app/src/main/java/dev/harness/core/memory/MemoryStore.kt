package dev.harness.core.memory

import dev.harness.net.HarnessJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/**
 * FARAI's long-term memory: plain Markdown files the agent (and the user) can read and edit.
 *
 * A memory filesystem built for a phone:
 *
 * - **Core memory** = every Markdown file at the root (`persona.md`, `human.md`, …) plus the root
 *   `MEMORY.md` index. Core files are compiled into the system prompt with their size and limit,
 *   so the agent can see how full each one is (memory pressure).
 * - **Deferred memory** = files in sub-folders (`projects/acme.md`). Only their folder index
 *   (`<folder>/MEMORY.md`, first paragraph) is in the prompt; the agent opens files on demand.
 *   Creating a file links it from its folder's index automatically, so nothing gets lost
 *   .
 * - **Project memory** = the same layout inside the workspace at `.farai/memory`, addressed as
 *   `project/…`. It stays with the project; agent memory travels across workspaces.
 * - **History** = every change is logged with the content before and after, the reason and the
 *   session it came from . Any change can be reverted.
 * - **Guards**: per-file and total limits with clear errors, no secrets (API keys, tokens and
 *   private keys are refused), paths cannot leave the memory folders, depth ≤ 2.
 */
class MemoryStore(
    val root: File,
    private val projectRootProvider: () -> File? = { null },
    private val seed: () -> Map<String, String> = { emptyMap() },
    val limits: MemoryLimits = MemoryLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val historyFile get() = File(root, ".history/log.jsonl")

    val projectRoot: File? get() = projectRootProvider()

    // ---- Setup ---------------------------------------------------------------------------

    /** Creates the memory folder and writes the default files the first time. Idempotent. */
    fun ensureSeeded() = synchronized(lock) {
        root.mkdirs()
        val marker = File(root, ".history/.seeded")
        if (marker.exists()) return@synchronized
        seed().forEach { (path, text) ->
            val f = File(root, path)
            if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(text) }
        }
        marker.parentFile?.mkdirs()
        marker.writeText(clock().toString())
    }

    // ---- Paths -----------------------------------------------------------------------------

    data class Resolved(val scope: Scope, val base: File, val rel: String) {
        val file: File get() = if (rel.isEmpty()) base else File(base, rel)
        /** The address the agent uses: `persona.md`, `people/ana.md`, `project/notes.md`. */
        val display: String get() = when (scope) { Scope.AGENT -> rel.ifEmpty { "/" }; Scope.PROJECT -> if (rel.isEmpty()) "project/" else "project/$rel" }
        val depth: Int get() = rel.count { it == '/' }
        val isRoot: Boolean get() = !rel.contains('/')
        val isIndex: Boolean get() = rel.substringAfterLast('/') == INDEX
    }

    enum class Scope { AGENT, PROJECT }

    /** Maps an address to a file, refusing anything that escapes the memory folders. */
    fun resolve(path: String?): Resolved {
        var p = (path ?: "").trim().replace('\\', '/')
        p = p.removePrefix("memory:").removePrefix("/memories/").removePrefix("/memory/").removePrefix("memory/").trimStart('/')
        if (p == "." || p == "/") p = ""
        val project = p == "project" || p.startsWith("project/")
        if (project) p = p.removePrefix("project").trimStart('/')
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." || it.startsWith(".") }) throw MemoryException("Paths can't contain '..' or hidden names: '$path'.")
        val base = if (project) projectRoot ?: throw MemoryException("There is no project workspace, so project/ memory isn't available.") else root
        return Resolved(if (project) Scope.PROJECT else Scope.AGENT, base, parts.joinToString("/"))
    }

    // ---- Reading ---------------------------------------------------------------------------

    fun exists(path: String): Boolean = runCatching { resolve(path).file.exists() }.getOrDefault(false)

    fun read(path: String): String {
        val r = resolve(path)
        val f = r.file
        if (f.isDirectory) return viewDir(r)
        if (!f.isFile) throw MemoryException("No memory file at '${r.display}'. Use view without a path to see what exists.")
        return f.readText()
    }

    /** Lines with numbers, for edits by line. */
    fun readNumbered(path: String, offset: Int = 1, limit: Int = 400): String {
        val r = resolve(path)
        val f = r.file
        if (f.isDirectory || r.rel.isEmpty()) return viewDir(r)
        if (!f.isFile) throw MemoryException("No memory file at '${r.display}'. Use view without a path to see what exists.")
        val lines = f.readText().lines()
        val start = (offset - 1).coerceIn(0, lines.size)
        val end = (start + limit).coerceAtMost(lines.size)
        return buildString {
            appendLine("${r.display} · ${f.length()} chars${limitFor(r, f.readText())?.let { " / limit $it" } ?: ""}")
            for (i in start until end) append(i + 1).append(": ").appendLine(lines[i])
            if (end < lines.size) append("… ${lines.size - end} more lines (use offset)")
        }.trimEnd()
    }

    private fun viewDir(r: Resolved): String {
        val dir = r.file
        if (!dir.isDirectory) throw MemoryException("No memory folder at '${r.display}'.")
        return buildString {
            val idx = File(dir, INDEX)
            if (r.rel.isEmpty()) {
                appendLine("Memory (${r.scope.name.lowercase()}): ${dir.absolutePath}")
            } else appendLine("Folder ${r.display}/")
            if (idx.isFile) { appendLine(); appendLine("${INDEX}:"); appendLine(idx.readText().trim().take(4000)) }
            appendLine()
            appendLine("Entries:")
            listEntries(dir).forEach { f ->
                val rel = (if (r.rel.isEmpty()) "" else r.rel + "/") + f.name
                val shown = if (r.scope == Scope.PROJECT) "project/$rel" else rel
                if (f.isDirectory) {
                    val count = f.walkTopDown().count { it.isFile && it.extension == "md" }
                    appendLine("- $shown/ — ${indexSummary(f) ?: "(no index)"} ($count files)")
                } else if (f.name != INDEX) {
                    val text = runCatching { f.readText() }.getOrDefault("")
                    val desc = MemoryFormat.parse(text).description ?: MemoryFormat.firstLine(text)
                    appendLine("- $shown — ${desc.take(160)} (${text.length} chars)")
                }
            }
            if (r.scope == Scope.AGENT && r.rel.isEmpty() && projectRoot?.isDirectory == true) appendLine("- project/ — memory of the current workspace")
        }.trimEnd()
    }

    private fun listEntries(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") && (it.isDirectory || it.extension == "md") }
            .sortedWith(compareBy<File>({ !it.isFile || it.name == INDEX }, { it.name.lowercase() }))

    /** The first prose paragraph of a folder's MEMORY.md. */
    fun indexSummary(dir: File): String? {
        val idx = File(dir, INDEX)
        if (!idx.isFile) return null
        return idx.readText().lines().map { it.trim() }
            .dropWhile { it.isEmpty() || it.startsWith("#") }
            .takeWhile { it.isNotEmpty() && !it.startsWith("- ") && !it.startsWith("* ") }
            .joinToString(" ").take(220).ifBlank { null }
    }

    /** Every memory file of both scopes (for search, the settings screen and tests). */
    fun allFiles(): List<Resolved> {
        val out = ArrayList<Resolved>()
        fun scan(scope: Scope, base: File?) {
            if (base == null || !base.isDirectory) return
            base.walkTopDown().onEnter { it == base || !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "md" }
                .forEach { out += Resolved(scope, base, it.relativeTo(base).invariantSeparatorsPath) }
        }
        scan(Scope.AGENT, root)
        scan(Scope.PROJECT, projectRoot)
        return out
    }

    // ---- Core memory (the part in the system prompt) -----------------------------------------

    data class Block(
        val path: String,
        val name: String,
        val description: String,
        val body: String,
        val chars: Int,
        val limit: Int,
        val readOnly: Boolean,
        val scope: Scope,
    )

    fun coreBlocks(scope: Scope = Scope.AGENT): List<Block> {
        val base = (if (scope == Scope.AGENT) root else projectRoot) ?: return emptyList()
        if (!base.isDirectory) return emptyList()
        return (base.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "md" && it.name != INDEX && !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ CORE_ORDER.indexOf(it.nameWithoutExtension).let { i -> if (i < 0) 99 else i } }, { it.name }))
            .map { f ->
                val text = f.readText()
                val doc = MemoryFormat.parse(text)
                val rel = f.name
                Block(
                    path = if (scope == Scope.PROJECT) "project/$rel" else rel,
                    name = doc.name ?: f.nameWithoutExtension,
                    description = doc.description ?: "",
                    body = doc.body.trim(),
                    chars = text.length,
                    limit = doc.limit ?: limits.coreFile,
                    readOnly = doc.readOnly,
                    scope = scope,
                )
            }
    }

    fun rootIndex(scope: Scope = Scope.AGENT): String? {
        val base = (if (scope == Scope.AGENT) root else projectRoot) ?: return null
        return File(base, INDEX).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    data class DeferredDir(val path: String, val summary: String?, val files: Int)

    fun deferredDirs(scope: Scope = Scope.AGENT): List<DeferredDir> {
        val base = (if (scope == Scope.AGENT) root else projectRoot) ?: return emptyList()
        return (base.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }.map { d ->
            DeferredDir(
                (if (scope == Scope.PROJECT) "project/" else "") + d.name + "/",
                indexSummary(d),
                d.walkTopDown().count { it.isFile && it.extension == "md" && it.name != INDEX },
            )
        }.filter { it.files > 0 || it.summary != null }
    }

    /** Total characters of all core files of one scope. */
    fun coreTotal(scope: Scope = Scope.AGENT): Int {
        val base = (if (scope == Scope.AGENT) root else projectRoot) ?: return 0
        return (base.listFiles() ?: emptyArray()).filter { it.isFile && it.extension == "md" }.sumOf { it.length().toInt() }
    }

    // ---- Writing ----------------------------------------------------------------------------

    /** Creates a file. Core and deferred files get name/description front matter; indexes don't. */
    fun create(path: String, content: String, description: String?, reason: String?, source: String?, overwrite: Boolean = false): String = synchronized(lock) {
        val r = resolveFile(path)
        val f = r.file
        if (f.exists() && !overwrite) throw MemoryException("'${r.display}' already exists. Read it and edit it, or pass overwrite: true to replace it.")
        if (r.depth > limits.maxDepth) throw MemoryException("'${r.display}' is ${r.depth} folders deep; the limit is ${limits.maxDepth}. Use a flatter path.")
        clashCheck(r)
        val text = if (r.isIndex) MemoryFormat.stripFrontMatter(content).trim() + "\n" else {
            val doc = MemoryFormat.parse(content)
            val desc = description?.trim()?.takeIf { it.isNotEmpty() } ?: doc.description
                ?: throw MemoryException("Give a one-line description of what '${r.display}' holds (description argument).")
            MemoryFormat.render(doc.copy(name = doc.name ?: f.nameWithoutExtension, description = desc))
        }
        val before = if (f.isFile) f.readText() else null
        commit(r, before, text, if (before == null) "create" else "overwrite", reason, source)
        if (!r.isIndex && !r.isRoot) linkFromIndex(r, MemoryFormat.parse(text).description ?: "", reason, source)
        "Created ${r.display} (${text.length} chars${limitFor(r, text)?.let { " / limit $it" } ?: ""})."
    }

    /** Replaces exactly one occurrence of [old] (or every one with [all]). */
    fun replace(path: String, old: String, new: String, all: Boolean, reason: String?, source: String?): String = synchronized(lock) {
        val r = resolveFile(path)
        val text = existing(r)
        if (old.isEmpty()) throw MemoryException("old_text is empty. Use append or insert to add text.")
        val count = text.split(old).size - 1
        if (count == 0) {
            val hint = MemoryFormat.closestLine(text, old)?.let { " Closest line: \"$it\"" } ?: ""
            throw MemoryException("old_text was not found in ${r.display}. Read the file first and copy the text exactly.$hint")
        }
        if (count > 1 && !all) throw MemoryException("old_text appears $count times in ${r.display}; include more surrounding text or pass replace_all: true.")
        val next = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        commit(r, text, next, "replace", reason, source)
        "Updated ${r.display}${sizeNote(r, next)}."
    }

    fun append(path: String, add: String, reason: String?, source: String?): String = synchronized(lock) {
        val r = resolveFile(path)
        val text = existing(r)
        val next = text.trimEnd('\n') + "\n" + add.trim('\n') + "\n"
        commit(r, text, next, "append", reason, source)
        "Appended to ${r.display}${sizeNote(r, next)}."
    }

    /** Inserts after line [line] (0 = at the top of the body, after any front matter). */
    fun insert(path: String, line: Int, add: String, reason: String?, source: String?): String = synchronized(lock) {
        val r = resolveFile(path)
        val text = existing(r)
        val lines = text.lines().toMutableList()
        val fmEnd = MemoryFormat.frontMatterLineCount(text)
        val at = (if (line <= 0) fmEnd else line).coerceIn(fmEnd, lines.size)
        lines.addAll(at, add.trim('\n').lines())
        val next = lines.joinToString("\n")
        commit(r, text, next, "insert", reason, source)
        "Inserted ${add.lines().size} line(s) into ${r.display} after line $at${sizeNote(r, next)}."
    }

    /** Rewrites a file's body; front matter is kept unless given. */
    fun rewrite(path: String, body: String, description: String?, reason: String?, source: String?): String = synchronized(lock) {
        val r = resolveFile(path)
        val text = existing(r)
        val next = if (r.isIndex) MemoryFormat.stripFrontMatter(body).trim() + "\n" else {
            val old = MemoryFormat.parse(text)
            val given = MemoryFormat.parse(body)
            MemoryFormat.render(old.copy(
                description = description?.trim()?.takeIf { it.isNotEmpty() } ?: given.description ?: old.description,
                body = given.body,
            ))
        }
        commit(r, text, next, "rewrite", reason, source)
        "Rewrote ${r.display}${sizeNote(r, next)}."
    }

    /** Writes a whole file as given (the user's editor). Same checks as every other change. */
    fun writeRaw(path: String, text: String, reason: String?, source: String?): String = synchronized(lock) {
        val r = resolveFile(path)
        val before = if (r.file.isFile) r.file.readText() else null
        commit(r, before, text.replace("\r\n", "\n"), if (before == null) "create" else "user-edit", reason, source)
        "Saved ${r.display}."
    }

    fun delete(path: String, reason: String?, source: String?): String = synchronized(lock) {
        val r = resolveFile(path)
        if (r.rel == INDEX) throw MemoryException("The root MEMORY.md can't be deleted; rewrite it instead.")
        val text = existing(r)
        commit(r, text, null, "delete", reason, source)
        unlinkFromIndex(r, reason, source)
        "Deleted ${r.display}. It can be restored from history."
    }

    fun move(from: String, to: String, reason: String?, source: String?): String = synchronized(lock) {
        val a = resolveFile(from)
        val b = resolveFile(to)
        val text = existing(a)
        if (b.file.exists()) throw MemoryException("'${b.display}' already exists.")
        if (b.depth > limits.maxDepth) throw MemoryException("'${b.display}' is too deep (limit ${limits.maxDepth}).")
        clashCheck(b)
        commit(b, null, text, "move", reason ?: "moved from ${a.display}", source)
        commit(a, text, null, "move", reason ?: "moved to ${b.display}", source)
        unlinkFromIndex(a, reason, source)
        if (!b.isIndex && !b.isRoot) linkFromIndex(b, MemoryFormat.parse(text).description ?: "", reason, source)
        "Moved ${a.display} → ${b.display}."
    }

    // ---- Search -----------------------------------------------------------------------------

    data class Hit(val path: String, val line: Int, val snippet: String, val score: Double)

    /** Keyword search over every memory file (BM25 over lines, with the file's name and description). */
    fun search(query: String, limit: Int = 10): List<Hit> {
        val docs = ArrayList<Pair<Resolved, List<String>>>()
        allFiles().forEach { r -> runCatching { docs += r to r.file.readText().lines() } }
        val units = ArrayList<Triple<Resolved, Int, String>>()
        docs.forEach { (r, lines) ->
            val head = MemoryFormat.parse(lines.joinToString("\n")).let { "${it.name ?: ""} ${it.description ?: ""} ${r.display}" }
            lines.forEachIndexed { i, l -> if (l.isNotBlank() && !l.startsWith("---")) units += Triple(r, i + 1, "$l \u0000$head") }
        }
        val ranked = Bm25.rank(query, units.map { it.third })
        return ranked.take(limit * 3).map { (i, score) ->
            val (r, line, text) = units[i]
            Hit(r.display, line, text.substringBefore(" \u0000").trim().take(240), score)
        }.distinctBy { it.path to it.line }.take(limit)
    }

    // ---- History ----------------------------------------------------------------------------

    @Serializable
    data class Change(
        val id: String,
        val time: Long,
        val op: String,
        val path: String,
        val reason: String? = null,
        val source: String? = null,
        val before: String? = null,
        val after: String? = null,
    )

    fun history(path: String? = null, limit: Int = 20): List<Change> {
        val f = historyFile
        if (!f.isFile) return emptyList()
        val want = path?.let { runCatching { resolve(it).display }.getOrNull() }
        return f.readLines().asReversed().asSequence()
            .mapNotNull { runCatching { HarnessJson.decodeFromString<Change>(it) }.getOrNull() }
            .filter { want == null || it.path == want }
            .take(limit).toList()
    }

    /** Puts a file back the way it was before change [id]. */
    fun revert(id: String, source: String?): String = synchronized(lock) {
        val c = history(limit = MAX_HISTORY).firstOrNull { it.id == id || it.id.startsWith(id) }
            ?: throw MemoryException("No memory change with id '$id'.")
        val r = resolve(c.path)
        val now = if (r.file.isFile) r.file.readText() else null
        commit(r, now, c.before, "revert", "revert ${c.id} (${c.op} ${c.path})", source)
        if (c.before == null) "Reverted ${c.id}: removed ${c.path}." else "Reverted ${c.id}: ${c.path} is back to how it was before (${c.before.length} chars)."
    }

    // ---- Internals --------------------------------------------------------------------------

    private fun resolveFile(path: String): Resolved {
        val r0 = resolve(path)
        if (r0.rel.isEmpty()) throw MemoryException("Give a file path, e.g. human.md or people/ana.md.")
        val r = if (r0.rel.endsWith(".md")) r0 else r0.copy(rel = r0.rel + ".md")
        if (r.file.isDirectory) throw MemoryException("'${r.display}' is a folder. Its index is ${r.display}/MEMORY.md.")
        return r
    }

    private fun existing(r: Resolved): String {
        if (!r.file.isFile) throw MemoryException("No memory file at '${r.display}'. Create it first (action: create).")
        return r.file.readText()
    }

    private fun clashCheck(r: Resolved) {
        val stem = r.rel.removeSuffix(".md")
        if (File(r.base, stem).isDirectory) throw MemoryException("A folder named '$stem/' exists; use another name or put the file inside it.")
    }

    private fun limitFor(r: Resolved, text: String): Int? = when {
        r.isRoot && r.rel != INDEX -> MemoryFormat.parse(text).limit ?: limits.coreFile
        r.isRoot -> limits.rootIndex
        else -> limits.file
    }

    private fun sizeNote(r: Resolved, text: String): String =
        limitFor(r, text)?.let { lim -> " (${text.length}/$lim chars${if (text.length > lim * 0.85) " — nearly full: condense it or move detail into a folder" else ""})" } ?: ""

    /** Validates and writes one change, then logs it. [after] null deletes the file. */
    private fun commit(r: Resolved, before: String?, after: String?, op: String, reason: String?, source: String?) {
        if (before != null && MemoryFormat.parse(before).readOnly && op != "revert" && op != "user-edit" && source != "user") throw MemoryException("'${r.display}' is read-only (read_only: true). Only the user can change it.")
        if (after != null) {
            Secrets.find(after)?.let { throw MemoryException("This looks like a secret ($it). Never store keys, tokens or passwords in memory; refer to them by name instead.") }
            limitFor(r, after)?.let { lim ->
                if (after.length > lim) throw MemoryException("${r.display} would be ${after.length} chars; its limit is $lim. Condense it (merge rules, drop what's stale) or move detail into a folder file and leave a pointer.")
            }
            if (r.isRoot) {
                val base = r.base
                val others = (base.listFiles() ?: emptyArray()).filter { it.isFile && it.extension == "md" && it.name != r.file.name }.sumOf { it.length().toInt() }
                if (others + after.length > limits.coreTotal) throw MemoryException("Core memory would be ${others + after.length} chars; the total limit is ${limits.coreTotal}. Move detail out of the root files into folders first.")
            }
            if (!r.isIndex && !MemoryFormat.hasFrontMatter(after)) throw MemoryException("${r.display} needs front matter with name and description.")
            if (r.isIndex && MemoryFormat.hasFrontMatter(after)) throw MemoryException("MEMORY.md files have no front matter.")
        }
        if (before == after) return
        val f = r.file
        if (after == null) f.delete() else {
            f.parentFile?.mkdirs()
            ensureIndexes(r)
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(after)
            if (!tmp.renameTo(f)) { f.writeText(after); tmp.delete() }
        }
        if (after == null) pruneEmptyDirs(f.parentFile, r.base)
        log(Change(newId(r.display + op), clock(), op, r.display, reason?.take(600), source, before, after))
    }

    /** Every folder on the way to a file gets its own MEMORY.md so it can be found. */
    private fun ensureIndexes(r: Resolved) {
        var dir = r.file.parentFile ?: return
        while (dir.canonicalPath.startsWith(r.base.canonicalPath) && dir.canonicalPath != r.base.canonicalPath) {
            val idx = File(dir, INDEX)
            if (!idx.exists()) idx.writeText("${dir.name.replace('-', ' ').replaceFirstChar { it.uppercase() }}.\n")
            dir = dir.parentFile ?: break
        }
    }

    private fun pruneEmptyDirs(start: File?, base: File) {
        var dir = start ?: return
        while (dir.canonicalPath != base.canonicalPath && dir.canonicalPath.startsWith(base.canonicalPath)) {
            val rest = (dir.listFiles() ?: emptyArray()).filter { it.name != INDEX }
            if (rest.isNotEmpty()) return
            val idx = File(dir, INDEX)
            // Keep a hand-written index; drop a stub.
            if (idx.isFile && idx.readText().lines().count { it.isNotBlank() } > 1) return
            idx.delete(); dir.delete()
            dir = dir.parentFile ?: return
        }
    }

    /** Adds "- [name](path) — description" to the parent folder's MEMORY.md when it isn't linked yet. */
    private fun linkFromIndex(r: Resolved, description: String, reason: String?, source: String?) {
        val parentRel = r.rel.substringBeforeLast('/', "")
        val idx = Resolved(r.scope, r.base, if (parentRel.isEmpty()) INDEX else "$parentRel/$INDEX")
        val fileName = r.rel.substringAfterLast('/')
        val text = if (idx.file.isFile) idx.file.readText() else ""
        if (text.contains(fileName)) return
        val link = "- [${fileName.removeSuffix(".md")}](${r.rel}) — ${description.replace('\n', ' ').take(160)}"
        val next = text.trimEnd() + (if (text.isBlank()) "" else "\n") + link + "\n"
        runCatching { commit(idx, text.ifEmpty { null }, next, "link", reason ?: "index link for ${r.display}", source) }
        // A deep file is also reachable from the root index through its top folder.
        val top = r.rel.substringBefore('/')
        if (parentRel.contains('/') || parentRel.isNotEmpty()) {
            val rootIdx = Resolved(r.scope, r.base, INDEX)
            val rootText = if (rootIdx.file.isFile) rootIdx.file.readText() else ""
            if (!rootText.contains("$top/")) {
                val line = "- [$top/]($top/$INDEX) — ${indexSummary(File(r.base, top)) ?: top}"
                runCatching { commit(rootIdx, rootText.ifEmpty { null }, rootText.trimEnd() + (if (rootText.isBlank()) "" else "\n") + line + "\n", "link", "index link for $top/", source) }
            }
        }
    }

    private fun unlinkFromIndex(r: Resolved, reason: String?, source: String?) {
        val parentRel = r.rel.substringBeforeLast('/', "")
        val idx = Resolved(r.scope, r.base, if (parentRel.isEmpty()) INDEX else "$parentRel/$INDEX")
        if (!idx.file.isFile) return
        val text = idx.file.readText()
        val name = r.rel.substringAfterLast('/')
        val next = text.lines().filterNot { l -> l.trimStart().startsWith("- ") && l.contains("($name)") || l.contains("(${r.rel})") }.joinToString("\n")
        if (next != text) runCatching { commit(idx, text, next, "unlink", reason ?: "removed link to ${r.display}", source) }
    }

    private fun log(c: Change) {
        val f = historyFile
        f.parentFile?.mkdirs()
        f.appendText(HarnessJson.encodeToString(c) + "\n")
        if (f.length() > MAX_HISTORY_BYTES) {
            val keep = f.readLines().takeLast(MAX_HISTORY)
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    private fun newId(salt: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$salt${clock()}${System.nanoTime()}".toByteArray())
        return d.take(4).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val INDEX = "MEMORY.md"
        val CORE_ORDER = listOf("persona", "human")
        const val MAX_HISTORY = 500
        const val MAX_HISTORY_BYTES = 3_000_000L
    }
}

data class MemoryLimits(
    /** Each root (core) file, front matter included; `limit:` in front matter overrides it. */
    val coreFile: Int = 6_000,
    /** The root MEMORY.md index. */
    val rootIndex: Int = 6_000,
    /** All root files together (what goes into every prompt). */
    val coreTotal: Int = 24_000,
    /** Any deferred file. */
    val file: Int = 20_000,
    /** Folders between the root and a file. */
    val maxDepth: Int = 2,
)

class MemoryException(message: String) : Exception(message)

/** Parsed front matter + body of a memory file. */
data class MemoryDoc(
    val name: String? = null,
    val description: String? = null,
    val limit: Int? = null,
    val readOnly: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
    val body: String = "",
)

object MemoryFormat {
    private val KNOWN = setOf("name", "description", "limit", "read_only")

    fun hasFrontMatter(text: String) = text.trimStart('\uFEFF').startsWith("---\n") || text.trimStart('\uFEFF').startsWith("---\r\n")

    fun parse(text: String): MemoryDoc {
        val t = text.trimStart('\uFEFF').replace("\r\n", "\n")
        if (!t.startsWith("---\n")) return MemoryDoc(body = t)
        val end = t.indexOf("\n---", 4)
        if (end < 0) return MemoryDoc(body = t)
        val head = t.substring(4, end)
        val body = t.substring(end + 4).removePrefix("\n")
        val map = LinkedHashMap<String, String>()
        head.lines().forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) map[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }
        return MemoryDoc(
            name = map["name"]?.ifBlank { null },
            description = map["description"]?.ifBlank { null },
            limit = map["limit"]?.toIntOrNull(),
            readOnly = map["read_only"]?.lowercase() == "true",
            extra = map.filterKeys { it !in KNOWN },
            body = body,
        )
    }

    fun render(d: MemoryDoc): String = buildString {
        appendLine("---")
        d.name?.let { appendLine("name: $it") }
        d.description?.let { appendLine("description: ${it.replace('\n', ' ')}") }
        d.limit?.let { appendLine("limit: $it") }
        if (d.readOnly) appendLine("read_only: true")
        d.extra.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("---")
        append(d.body.trim('\n'))
        append("\n")
    }

    fun stripFrontMatter(text: String): String = parse(text).body

    fun frontMatterLineCount(text: String): Int {
        val t = text.replace("\r\n", "\n")
        if (!t.startsWith("---\n")) return 0
        val end = t.indexOf("\n---", 4)
        if (end < 0) return 0
        return t.substring(0, end + 4).count { it == '\n' } + 1
    }

    fun firstLine(text: String): String = parse(text).body.lines().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() } ?: "(empty)"

    /** The line most like [needle], to help the model fix a failed replace. */
    fun closestLine(text: String, needle: String): String? {
        val first = needle.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val words = first.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return null
        return text.lines().maxByOrNull { l -> l.lowercase().split(Regex("\\W+")).count { it in words } }
            ?.takeIf { l -> l.lowercase().split(Regex("\\W+")).count { it in words } > 0 }?.trim()?.take(200)
    }
}

/** Refuses obvious credentials so they never end up in the prompt or in history. */
object Secrets {
    private val patterns = listOf(
        "an OpenAI/OpenRouter-style key" to Regex("\\bsk-(?:or-|proj-|ant-)?[A-Za-z0-9_\\-]{20,}"),
        "a GitHub token" to Regex("\\b(?:ghp|gho|ghs|ghu|github_pat)_[A-Za-z0-9_]{20,}"),
        "a Google API key" to Regex("\\bAIza[0-9A-Za-z_\\-]{30,}"),
        "an AWS key" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "a Slack token" to Regex("\\bxox[abprs]-[A-Za-z0-9\\-]{10,}"),
        "a private key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        "a bearer token" to Regex("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{30,}"),
        "a password" to Regex("(?i)\\b(?:password|passwd|kata ?sandi)\\s*[:=]\\s*\\S{6,}"),
    )

    fun find(text: String): String? = patterns.firstOrNull { it.second.containsMatchIn(text) }?.first
}

/** Small BM25 ranker; tokens are lowercase letters/digits, so it works for English and Indonesian. */
object Bm25 {
    private val split = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(s: String): List<String> = s.lowercase().split(split).filter { it.length > 1 }

    /** Returns (index, score) of the documents that match, best first. */
    fun rank(query: String, docs: List<String>, k1: Double = 1.4, b: Double = 0.7): List<Pair<Int, Double>> {
        val q = tokens(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val toks = docs.map { tokens(it) }
        val avg = toks.sumOf { it.size }.toDouble() / toks.size.coerceAtLeast(1)
        val df = HashMap<String, Int>()
        toks.forEach { t -> t.toSet().forEach { w -> if (q.any { qq -> w.startsWith(qq) }) df[w] = (df[w] ?: 0) + 1 } }
        val n = docs.size
        val phrase = query.trim().lowercase().takeIf { it.contains(' ') }
        return toks.mapIndexedNotNull { i, t ->
            if (t.isEmpty()) return@mapIndexedNotNull null
            val tf = HashMap<String, Int>()
            t.forEach { w -> tf[w] = (tf[w] ?: 0) + 1 }
            var score = 0.0
            var matched = 0
            for (qq in q) {
                // Prefix match lets "rilis" find "rilisnya" and "deploy" find "deployment".
                val hits = tf.entries.filter { it.key == qq || (qq.length >= 4 && it.key.startsWith(qq)) }
                if (hits.isEmpty()) continue
                matched++
                for ((w, f) in hits) {
                    val d = df[w] ?: 1
                    val idf = kotlin.math.ln(1 + (n - d + 0.5) / (d + 0.5))
                    val exact = if (w == qq) 1.0 else 0.6
                    score += exact * idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * t.size / avg))
                }
            }
            if (matched == 0) return@mapIndexedNotNull null
            score *= (0.5 + 0.5 * matched / q.size)
            if (phrase != null && docs[i].lowercase().contains(phrase)) score *= 1.8
            i to score
        }.sortedByDescending { it.second }
    }
}
