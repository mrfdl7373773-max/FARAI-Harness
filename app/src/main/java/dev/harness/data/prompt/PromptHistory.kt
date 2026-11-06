package dev.harness.data.prompt

import java.io.File

data class HistoryEntry(
    val path: String,
    val id: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val file: File,
)

/**
 * Version history for prompt files: filesDir/prompts/.history/<encoded path>/<timestamp>.md,
 * keeping the newest [limit] versions per file.
 */
class PromptHistory(private val historyRoot: File, private val limit: Int = 50) {

    private fun dirFor(path: String) = File(historyRoot, path.replace("/", "__"))

    @Synchronized
    fun record(path: String, content: String, now: Long = System.currentTimeMillis()): HistoryEntry {
        val dir = dirFor(path).apply { mkdirs() }
        var ts = now
        var f = File(dir, "$ts.md")
        while (f.exists()) { ts++; f = File(dir, "$ts.md") }
        f.writeText(content)
        prune(dir)
        return HistoryEntry(path, f.nameWithoutExtension, ts, f.length(), f)
    }

    fun list(path: String): List<HistoryEntry> {
        val dir = dirFor(path)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return emptyList()
        return files.mapNotNull { f ->
            f.nameWithoutExtension.toLongOrNull()?.let { HistoryEntry(path, f.nameWithoutExtension, it, f.length(), f) }
        }.sortedByDescending { it.timestamp }
    }

    fun read(path: String, id: String): String? {
        val f = File(dirFor(path), "$id.md")
        return if (f.isFile) f.readText() else null
    }

    fun clear(path: String) { dirFor(path).deleteRecursively() }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L } ?: return
        files.drop(limit).forEach { it.delete() }
    }
}
