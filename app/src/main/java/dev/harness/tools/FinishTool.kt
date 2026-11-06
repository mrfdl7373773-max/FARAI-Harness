package dev.harness.tools

import kotlinx.serialization.json.JsonObject

class FinishTool : Tool {
    override val name = "finish"
    override val parameters: JsonObject = schema { string("summary", required = true) }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(args.reqStr("summary"), finish = true)
}
