package dev.harness.tools

import java.io.File

/**
 * The sandbox root (filesDir/workspace). Every path a tool touches is resolved through here.
 *
 * [extraRoots] lists other folders the tools may reach, such as shared storage once the user
 * grants All files access. Paths are accepted the way the Linux shell sees them too:
 * `/workspace/...` maps to [root] and `/sdcard/...` to the first extra root.
 */
class Workspace(rootDir: File, private val extraRoots: () -> List<File> = { emptyList() }) {
    val root: File = rootDir.apply { mkdirs() }.canonicalFile

    fun allowedRoots(): List<File> = listOf(root) + extraRoots().map { it.canonicalFile }

    /** Resolves a relative or absolute path; rejects anything outside the allowed roots. */
    fun resolve(path: String): File {
        val cleaned = path.trim().ifEmpty { "." }
        val mapped = mapGuestPath(cleaned)
        val candidate = File(mapped).let { if (it.isAbsolute) it else File(root, mapped) }.canonicalFile
        if (allowedRoots().none { r -> candidate == r || candidate.path.startsWith(r.path + File.separator) }) {
            val hint = if (extraRoots().isEmpty()) " (grant All files access in Settings to reach shared storage)" else ""
            throw ToolArgumentException("path '$path' is outside the workspace$hint")
        }
        return candidate
    }

    private fun mapGuestPath(p: String): String {
        if (p == "/workspace" || p.startsWith("/workspace/")) return root.path + p.removePrefix("/workspace")
        val sd = extraRoots().firstOrNull()
        if (sd != null && (p == "/sdcard" || p.startsWith("/sdcard/"))) return sd.path + p.removePrefix("/sdcard")
        return p
    }

    /** Path for display: relative inside the workspace, absolute elsewhere. */
    fun relative(file: File): String {
        val f = file.canonicalFile
        return if (f == root || f.path.startsWith(root.path + File.separator)) f.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
        else f.path
    }

    companion object {
        val SKIP_DIRS = setOf(".git", ".gradle", "build", "node_modules", ".idea", "__pycache__", ".venv", "dist", "target")

        fun isProbablyBinary(f: File): Boolean {
            if (!f.isFile) return false
            val buf = ByteArray(minOf(8000L, f.length()).toInt())
            f.inputStream().use { it.read(buf) }
            return buf.any { it == 0.toByte() }
        }
    }
}
