package dev.harness.tools

import dev.harness.core.LineDiff
import dev.harness.core.permission.PermissionEngine
import java.io.File

/** Shared steps for tools that read or change files: permission, snapshot, atomic write, diff. */
internal object FileOps {

    fun isInsideWorkspace(ctx: ToolContext, f: File): Boolean {
        val root = ctx.workspace.root
        return f.canonicalFile == root || f.canonicalPath.startsWith(root.path + File.separator)
    }

    /** Paths outside the workspace (shared storage) go through `external_directory` first. */
    suspend fun authorizeExternal(ctx: ToolContext, f: File): String? {
        if (isInsideWorkspace(ctx, f)) return null
        val dir = (if (f.isDirectory) f else f.parentFile ?: f).path
        return ctx.permission.check("external_directory", listOf(dir), "Access outside the workspace?", f.path, listOf("${dir}/*"))
    }

    suspend fun authorizeRead(ctx: ToolContext, tool: String, f: File): String? {
        authorizeExternal(ctx, f)?.let { return it }
        val rel = ctx.workspace.relative(f)
        return ctx.permission.check(tool, listOf(rel), "Allow $tool on $rel?", rel, listOf(PermissionEngine.pathPattern(rel)))
    }

    suspend fun authorizeEdit(ctx: ToolContext, f: File, preview: String): String? {
        authorizeExternal(ctx, f)?.let { return it }
        val rel = ctx.workspace.relative(f)
        return ctx.permission.check("edit", listOf(rel), "Change $rel?", preview.ifBlank { rel }, listOf(PermissionEngine.pathPattern(rel)))
    }

    fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.farai.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun diff(rel: String, before: String, after: String, maxLines: Int = 120): String {
        val d = LineDiff.unified("a/$rel", "b/$rel", before.replace("\r\n", "\n"), after.replace("\r\n", "\n"), context = 2)
        val lines = d.lines()
        return if (lines.size <= maxLines) d else lines.take(maxLines).joinToString("\n") + "\n… (${lines.size - maxLines} more diff lines)"
    }

    fun countLines(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1

    /** Short "+3 -1" stat for a diff. */
    fun stat(diff: String): String {
        val add = diff.lines().count { it.startsWith("+") && !it.startsWith("+++") }
        val del = diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
        return "+$add -$del"
    }
}
