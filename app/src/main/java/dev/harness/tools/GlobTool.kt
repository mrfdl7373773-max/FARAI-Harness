package dev.harness.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class GlobTool : Tool {
    override val name = "glob"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("pattern", required = true)
        string("path")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args.reqStr("pattern").removePrefix("./")
        val base = ctx.workspace.resolve(args.str("path") ?: ".")
        if (!base.isDirectory) return@withContext ToolResult.error("not a directory: ${args.str("path")}")
        val regex = Glob.toRegex(pattern)
        val hasSlash = pattern.contains('/')
        val matches = base.walkTopDown()
            .onEnter { it == base || it.name !in Workspace.SKIP_DIRS }
            .filter { it.isFile }
            .filter { Glob.matches(regex, it.relativeTo(base).invariantSeparatorsPath, hasSlash) }
            .sortedByDescending { it.lastModified() }
            .take(MAX + 1)
            .toList()
        if (matches.isEmpty()) return@withContext ToolResult("no files match '$pattern'")
        val shown = matches.take(MAX).joinToString("\n") { ctx.workspace.relative(it) }
        ToolResult(shown + if (matches.size > MAX) "\n… (more than $MAX matches; narrow the pattern)" else "")
    }

    companion object { const val MAX = 200 }
}
