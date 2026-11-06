package dev.harness.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

class GrepTool : Tool {
    override val name = "grep"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("pattern", required = true)
        string("path")
        string("include")
        boolean("ignore_case")
        boolean("literal")
        integer("max_results")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val options = if (args.bool("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = try {
            val p = args.reqStr("pattern")
            if (args.bool("literal") == true) Regex(Regex.escape(p), options) else Regex(p, options)
        } catch (e: Exception) {
            return@withContext ToolResult.error("invalid regex: ${e.message}")
        }
        val max = (args.int("max_results") ?: 100).coerceIn(1, 2000)
        val base = ctx.workspace.resolve(args.str("path") ?: ".")
        val include = args.str("include")?.let { Glob.toRegex(it) }
        val files = if (base.isFile) sequenceOf(base) else base.walkTopDown()
            .onEnter { it == base || it.name !in Workspace.SKIP_DIRS }
            .filter { it.isFile }
        val out = StringBuilder()
        var hits = 0
        var filesWithHits = 0
        loop@ for (f in files) {
            coroutineContext.ensureActive()
            if (include != null && !include.matches(f.name)) continue
            if (f.length() > 5_000_000 || Workspace.isProbablyBinary(f)) continue
            var fileHit = false
            f.useLines { lines ->
                lines.forEachIndexed { i, line ->
                    if (hits >= max) return@useLines
                    if (regex.containsMatchIn(line)) {
                        if (!fileHit) { fileHit = true; filesWithHits++ }
                        out.append(ctx.workspace.relative(f)).append(':').append(i + 1).append(": ")
                            .appendLine(if (line.length > 300) line.take(300) + " …" else line)
                        hits++
                    }
                }
            }
            if (hits >= max) break@loop
        }
        if (hits == 0) return@withContext ToolResult("no matches for /${args.reqStr("pattern")}/")
        val note = if (hits >= max) "\n… (stopped at $max matches)" else ""
        ToolResult("$hits matches in $filesWithHits files\n" + out.toString().trimEnd() + note)
    }
}
