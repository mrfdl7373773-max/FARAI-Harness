package dev.harness.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class WriteTool : Tool {
    override val name = "write"
    override val parameters: JsonObject = schema {
        string("path", required = true)
        string("content", required = true)
        boolean("append")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val f = ctx.workspace.resolve(path)
        val content = args.reqStr("content")
        if (f.isDirectory) return@withContext ToolResult.error("'$path' is a directory")
        val rel = ctx.workspace.relative(f)
        val existed = f.exists()
        val append = args.bool("append") == true
        // Replacing a file the model has never looked at is how user work gets destroyed.
        if (existed && !append && !ctx.session.hasSeen(f) && f.length() > 0) {
            return@withContext ToolResult.error(
                "$rel already exists (${FileOps.countLines(f.readText())} lines) and you have not read it in this session. " +
                    "Read it first, then use edit for targeted changes, or write again if you really mean to replace it."
            )
        }
        val before = if (existed) f.readText() else ""
        val after = if (append) before + content else content
        val preview = if (existed) FileOps.diff(rel, before, after, 60) else content.take(3000)
        FileOps.authorizeEdit(ctx, f, preview)?.let { return@withContext ToolResult.error(it) }
        ctx.beforeMutate(f)
        if (append) { f.parentFile?.mkdirs(); f.appendText(content) } else FileOps.writeAtomic(f, content)
        ctx.session.markSeen(f)
        val lines = FileOps.countLines(after)
        when {
            append -> ToolResult("appended ${FileOps.countLines(content)} lines to $rel (now $lines lines, ${f.length()} bytes)")
            existed -> ToolResult("overwrote $rel ($lines lines, ${f.length()} bytes), ${FileOps.stat(FileOps.diff(rel, before, after, Int.MAX_VALUE))}")
            else -> ToolResult("created $rel ($lines lines, ${f.length()} bytes)")
        }
    }
}
