package dev.harness.core

import java.io.File

/**
 * Before the agent changes a file with write / edit / multiedit / patch, the original bytes are
 * copied here, keyed by session and checkpoint (the number of user messages so far). `/undo` uses
 * them to put every file back the way it was before the last exchange, not only the chat.
 *
 * Changes made through bash (sed -i, npm install, ...) are not tracked.
 */
class FileHistory(private val root: File) {

    private fun dir(sessionId: String, checkpoint: Int) = File(root, "${safe(sessionId)}/$checkpoint")

    /** Records [file]'s current state once per checkpoint (the first snapshot wins). */
    @Synchronized
    fun snapshot(sessionId: String, checkpoint: Int, file: File) {
        val d = dir(sessionId, checkpoint).apply { mkdirs() }
        val index = File(d, INDEX)
        val path = file.canonicalPath
        val known = if (index.isFile) index.readLines() else emptyList()
        if (known.any { it.substringAfter('\t') == path }) return
        val n = known.size
        if (file.isFile) file.copyTo(File(d, "$n.bin"), overwrite = true)
        index.appendText("${if (file.isFile) "F" else "N"}$n\t$path\n")
    }

    /** Files changed at [checkpoint]. */
    fun changed(sessionId: String, checkpoint: Int): List<String> {
        val index = File(dir(sessionId, checkpoint), INDEX)
        return if (index.isFile) index.readLines().filter { it.isNotBlank() }.map { it.substringAfter('\t') } else emptyList()
    }

    /** Restores every file recorded at [checkpoint] and later. Returns the restored paths. */
    @Synchronized
    fun restoreFrom(sessionId: String, checkpoint: Int): List<String> {
        val sessionDir = File(root, safe(sessionId))
        val points = sessionDir.listFiles()?.mapNotNull { it.name.toIntOrNull() }?.filter { it >= checkpoint }?.sortedDescending().orEmpty()
        val restored = LinkedHashSet<String>()
        // Newest first, so the oldest snapshot of a file is the one that sticks.
        for (cp in points) {
            val d = dir(sessionId, cp)
            val index = File(d, INDEX)
            if (index.isFile) index.readLines().filter { it.isNotBlank() }.forEach { line ->
                val tag = line.substringBefore('\t')
                val path = line.substringAfter('\t')
                val target = File(path)
                if (tag.startsWith("F")) {
                    target.parentFile?.mkdirs()
                    File(d, "${tag.drop(1)}.bin").copyTo(target, overwrite = true)
                } else target.delete()
                restored += path
            }
            d.deleteRecursively()
        }
        return restored.toList()
    }

    fun dropSession(sessionId: String) { File(root, safe(sessionId)).deleteRecursively() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object { private const val INDEX = "index.tsv" }
}
