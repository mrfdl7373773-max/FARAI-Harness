package dev.harness.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

class EditTool : Tool {
    override val name = "edit"
    override val parameters: JsonObject = schema {
        string("path", required = true)
        string("old_string", required = true)
        string("new_string", required = true)
        boolean("replace_all")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val f = ctx.workspace.resolve(path)
        if (f.isDirectory) return@withContext ToolResult.error("'$path' is a directory, not a file")
        if (!f.isFile) return@withContext ToolResult.error("file not found: $path. Use write to create a new file.")
        val rel = ctx.workspace.relative(f)
        val before = f.readText()
        when (val r = TextMatch.replace(before, args.reqStr("old_string"), args.reqStr("new_string"), args.bool("replace_all") == true, rel)) {
            is TextMatch.Result.Fail -> ToolResult.error(r.message)
            is TextMatch.Result.Ok -> {
                val diff = FileOps.diff(rel, before, r.text)
                FileOps.authorizeEdit(ctx, f, diff)?.let { return@withContext ToolResult.error(it) }
                ctx.beforeMutate(f)
                FileOps.writeAtomic(f, r.text)
                ctx.session.markSeen(f)
                val tier = if (r.tier == "exact") "" else " (matched ignoring ${r.tier.replace('-', ' ')})"
                ToolResult("edited $rel: ${r.count} replacement${if (r.count == 1) "" else "s"} at line ${r.firstLine}$tier, ${FileOps.stat(diff)}\n$diff")
            }
        }
    }
}

/** Several edits to one file, applied in order and written once: all succeed or nothing changes. */
class MultiEditTool : Tool {
    override val name = "multiedit"
    override val parameters: JsonObject = schema {
        string("path", required = true)
        array("edits", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("old_string") { put("type", "string") }
                putJsonObject("new_string") { put("type", "string") }
                putJsonObject("replace_all") { put("type", "boolean") }
            }
            putJsonArray("required") { add(JsonPrimitive("old_string")); add(JsonPrimitive("new_string")) }
        }, required = true)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val f = ctx.workspace.resolve(path)
        val edits = args.array("edits") ?: return@withContext ToolResult.error("'edits' must be a list of {old_string, new_string}")
        if (edits.isEmpty()) return@withContext ToolResult.error("'edits' is empty")
        val rel = ctx.workspace.relative(f)
        // First edit with an empty old_string on a missing file creates it.
        val creating = !f.exists()
        if (f.isDirectory) return@withContext ToolResult.error("'$path' is a directory")
        val before = if (creating) "" else f.readText()
        var text = before
        var total = 0
        edits.forEachIndexed { i, el ->
            val o = el.jsonObject
            val old = o.str("old_string") ?: ""
            val new = o.str("new_string") ?: return@withContext ToolResult.error("edit ${i + 1}: missing new_string")
            if (i == 0 && creating && old.isEmpty()) { text = new; total++; return@forEachIndexed }
            if (creating && i == 0) return@withContext ToolResult.error("file not found: $path. To create it, make the first edit's old_string empty.")
            when (val r = TextMatch.replace(text, old, new, o.bool("replace_all") == true, "$rel (edit ${i + 1} of ${edits.size})")) {
                is TextMatch.Result.Fail -> return@withContext ToolResult.error("edit ${i + 1} failed, nothing was written: ${r.message}")
                is TextMatch.Result.Ok -> { text = r.text; total += r.count }
            }
        }
        val diff = FileOps.diff(rel, before, text)
        FileOps.authorizeEdit(ctx, f, diff)?.let { return@withContext ToolResult.error(it) }
        ctx.beforeMutate(f)
        FileOps.writeAtomic(f, text)
        ctx.session.markSeen(f)
        ToolResult("${if (creating) "created" else "edited"} $rel: ${edits.size} edits, $total replacements, ${FileOps.stat(diff)}\n$diff")
    }
}
