package dev.harness.core

import dev.harness.data.prompt.HistoryEntry
import dev.harness.data.prompt.PromptFile
import dev.harness.data.prompt.PromptGroup
import dev.harness.data.prompt.PromptHistory
import dev.harness.data.prompt.PromptMissingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The single source of truth for every editable prompt.
 *
 * Files live under [root] (filesDir/prompts). Nothing is cached: every read goes to disk,
 * so an edit saved from the editor is what the very next request sends.
 */
class PromptRepository(
    val root: File,
    private val defaults: DefaultsSource,
    historyLimit: Int = 50,
) {
    private val historyDir = File(root, HISTORY_DIR)
    private val seededMarker = File(root, SEEDED_MARKER)
    val history = PromptHistory(historyDir, historyLimit)

    private val _version = MutableStateFlow(0L)
    /** Increments on every write/reset/import so UIs can refresh. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** True when the prompts folder has never been created (first launch). */
    val isFirstRun: Boolean get() = !root.isDirectory

    /**
     * Copies defaults on first launch. On later launches, copies only default files that were
     * added in an app update and never seeded before; user edits and user deletions are kept.
     * Returns true when this was the first launch.
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        val first = isFirstRun
        root.mkdirs()
        val seeded = if (seededMarker.isFile) seededMarker.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        var changed = false
        for (path in defaults.list()) {
            if (path in seeded) {
                if (upgradeUntouched(path)) changed = true
                continue
            }
            val target = fileFor(path)
            if (!target.exists()) {
                defaults.read(path)?.let { content ->
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                }
            }
            seeded += path
            changed = true
        }
        if (changed || !seededMarker.exists()) seededMarker.writeText(seeded.sorted().joinToString("\n"))
        recordShippedHashes()
        if (changed) bump()
        return first
    }

    /**
     * A default that changed in an app update replaces the file on disk only when the user never
     * edited it: its content still matches a default this app shipped before.
     */
    private fun upgradeUntouched(path: String): Boolean {
        val target = fileFor(path)
        if (!target.isFile) return false
        val current = defaults.read(path) ?: return false
        val onDisk = target.readText()
        if (onDisk == current) return false
        val hashes = shippedHashes()
        val diskHash = sha256(onDisk)
        val known = hashes[path]?.contains(diskHash) == true || diskHash in LEGACY_DEFAULT_HASHES
        if (!known) return false
        target.writeText(current)
        return true
    }

    private val hashFile = File(root, ".default-hashes")

    private fun shippedHashes(): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        if (hashFile.isFile) hashFile.readLines().forEach { line ->
            val (p, h) = line.split('\t').takeIf { it.size == 2 } ?: return@forEach
            map.getOrPut(p) { mutableSetOf() } += h
        }
        return map
    }

    /** Remembers the hash of every default shipped by this version, for future upgrades. */
    private fun recordShippedHashes() {
        val map = shippedHashes().mapValues { it.value.toMutableSet() }.toMutableMap()
        var changed = false
        for (p in defaults.list()) {
            val h = defaults.read(p)?.let(::sha256) ?: continue
            val set = map.getOrPut(p) { mutableSetOf() }
            if (set.add(h)) changed = true
        }
        if (changed || !hashFile.isFile) {
            hashFile.writeText(map.entries.sortedBy { it.key }.flatMap { e -> e.value.sorted().map { "${e.key}\t$it" } }.joinToString("\n"))
        }
    }

    fun list(): List<PromptFile> {
        val defaultPaths = defaults.list().toSet()
        val onDisk = if (root.isDirectory) {
            root.walkTopDown()
                .onEnter { it.name != HISTORY_DIR }
                .filter { it.isFile && it.name.endsWith(".md") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toSet()
        } else emptySet()
        return (defaultPaths + onDisk).map { describe(it, it in defaultPaths) }
            .sortedWith(compareBy({ it.group.order }, { it.path }))
    }

    fun describe(path: String, hasDefault: Boolean = defaults.read(path) != null): PromptFile {
        val f = fileFor(path)
        val exists = f.isFile
        val modified = if (exists && hasDefault) f.readText() != defaults.read(path) else !hasDefault
        return PromptFile(
            path = path,
            group = PromptGroup.of(path),
            sizeBytes = if (exists) f.length() else 0,
            lastModified = if (exists) f.lastModified() else 0,
            exists = exists,
            hasDefault = hasDefault,
            modified = modified,
        )
    }

    fun exists(path: String): Boolean = fileFor(path).isFile

    /** Reads a prompt from disk, every time. Throws [PromptMissingException] when absent. */
    fun read(path: String): String {
        val f = fileFor(path)
        if (!f.isFile) throw PromptMissingException(path)
        return f.readText()
    }

    fun readOrNull(path: String): String? = fileFor(path).takeIf { it.isFile }?.readText()

    /** Saves content and appends a version to history. */
    @Synchronized
    fun write(path: String, content: String) {
        val f = fileFor(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            f.writeText(content)
            tmp.delete()
        }
        history.record(normalize(path), content)
        bump()
    }

    fun defaultContent(path: String): String? = defaults.read(normalize(path))

    /** Restores one file from the shipped defaults. Returns false when no default exists. */
    fun reset(path: String): Boolean {
        val content = defaultContent(path) ?: return false
        write(path, content)
        return true
    }

    /** Restores every shipped default. Files the user created (no default) are left in place. */
    fun resetAll(): Int {
        var n = 0
        for (p in defaults.list()) if (reset(p)) n++
        return n
    }

    /** Deletes a user-created prompt. Shipped prompts cannot be deleted, only reset. */
    fun delete(path: String): Boolean {
        if (defaultContent(path) != null) return false
        val ok = fileFor(path).delete()
        if (ok) bump()
        return ok
    }

    fun diffRows(path: String): List<DiffRow> =
        LineDiff.sideBySide(defaultContent(path) ?: "", readOrNull(path) ?: "")

    fun unifiedDiff(path: String): String =
        LineDiff.unified("default/$path", "current/$path", defaultContent(path) ?: "", readOrNull(path) ?: "")

    fun historyOf(path: String): List<HistoryEntry> = history.list(normalize(path))

    fun rollback(path: String, versionId: String): Boolean {
        val content = history.read(normalize(path), versionId) ?: return false
        write(path, content)
        return true
    }

    /** Writes every prompt (not history) into a zip stream. */
    fun exportZip(out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out).use { zip ->
            for (p in list().filter { it.exists }) {
                zip.putNextEntry(ZipEntry("prompts/${p.path}"))
                zip.write(fileFor(p.path).readBytes())
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    /** Loads .md entries from a zip (with or without a leading "prompts/" folder). */
    fun importZip(input: InputStream): Int {
        var n = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".md")) {
                    val rel = e.name.removePrefix("/").removePrefix("prompts/")
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    runCatching { write(rel, content) }.onSuccess { n++ }
                }
                zip.closeEntry()
            }
        }
        return n
    }

    /** Resolves a user-facing name ("identity", "bash", "tools/bash", "tools/bash.md") to a path. */
    fun resolveName(name: String): String? {
        val n = name.trim().removeSuffix(".md")
        if (n.isEmpty()) return null
        val all = list().map { it.path }
        return all.firstOrNull { it == "$n.md" }
            ?: all.firstOrNull { it.removeSuffix(".md").substringAfterLast('/') == n }
            ?: all.firstOrNull { it.removeSuffix(".md").replace('/', '.') == n.replace('/', '.') }
    }

    fun fileFor(path: String): File = File(root, normalize(path))

    private fun normalize(path: String): String {
        val p = path.trim().replace('\\', '/').removePrefix("/")
        require(p.isNotEmpty()) { "Empty prompt path" }
        require(p.endsWith(".md")) { "Prompt files must end with .md: $p" }
        val parts = p.split('/')
        require(parts.none { it == ".." || it == "." || it.isEmpty() }) { "Invalid prompt path: $p" }
        require(!p.startsWith("$HISTORY_DIR/")) { "History is read-only: $p" }
        return p
    }

    private fun bump() { _version.value = _version.value + 1 }

    companion object {
        const val HISTORY_DIR = ".history"
        private const val SEEDED_MARKER = ".seeded"

        /** Hashes of defaults shipped in v1.0.0, before shipped hashes were recorded. */
        private val LEGACY_DEFAULT_HASHES = setOf(
            "f2c84f90c6ef4b2944b3d412e9e22120a7cbdbd5b92e2daa977fbf71975b35c8", // tools/bash.md
            "6ec6920e8a1324bc7ec3630a923e5cc631df30f4da6dff0e8a4737119a53c2af", // error_recovery.md
        )

        private fun sha256(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
