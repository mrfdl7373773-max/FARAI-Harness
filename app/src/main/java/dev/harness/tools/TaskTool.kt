package dev.harness.tools

import kotlinx.serialization.json.JsonObject

class TaskTool : Tool {
    override val name = "task"
    /** Several subagents from one reply run side by side. */
    override val parallelSafe = true
    override val parameters: JsonObject = schema {
        string("description", required = true)
        string("prompt", required = true)
        string("agent")
        string("task_id")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val runner = ctx.subagent ?: return ToolResult.error("subagents are disabled here (Settings → Agent → Subagents, and subagents cannot start their own)")
        val description = args.reqStr("description")
        val prompt = args.reqStr("prompt")
        val agent = args.str("agent")?.trim()?.takeIf { it.isNotEmpty() }
        if (agent != null) {
            val known = ctx.subagentNames().map { it.first.lowercase() }
            if (known.isNotEmpty() && agent.lowercase() !in known) {
                return ToolResult.error("unknown agent '$agent'. Available: ${ctx.subagentNames().joinToString { it.first }}")
            }
        }
        ctx.permission.check("task", listOf(agent ?: "general"), "Start a subagent?", "$description\n\n$prompt".take(1500), listOf(agent ?: "general"))
            ?.let { return ToolResult.error(it) }
        return try {
            val r = runner.run(agent, description, prompt, args.str("task_id")?.takeIf { it.isNotBlank() })
            ToolResult(r.text.ifBlank { "(the subagent returned no text)" } + "\n\n<task_id>${r.sessionId}</task_id> (pass it as task_id to send this ${r.agent} subagent a follow-up)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("subagent failed: ${e.message}")
        }
    }
}
