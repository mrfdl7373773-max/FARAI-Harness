package dev.harness.tools

import kotlinx.serialization.json.JsonObject

/** Looks after commands started with bash(background: true). */
class JobTool : Tool {
    override val name = "job"
    override val parameters: JsonObject = schema {
        string("action", required = true, enum = listOf("list", "output", "wait", "kill"))
        integer("id")
        integer("timeout_seconds")
        integer("max_chars")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobs = ctx.jobs ?: return ToolResult.error("background jobs are not available here")
        val action = args.reqStr("action")
        if (action == "list") {
            val list = jobs.list(ctx.sessionId)
            if (list.isEmpty()) return ToolResult("no background jobs in this session")
            return ToolResult(list.joinToString("\n") { describe(it, ctx) })
        }
        val id = args.int("id") ?: return ToolResult.error("'id' is required for $action")
        val job = jobs.get(id)?.takeIf { it.sessionId == ctx.sessionId } ?: return ToolResult.error("no background job #$id in this session")
        val max = (args.int("max_chars") ?: 6000).coerceIn(200, 60_000)
        return when (action) {
            "output" -> ToolResult(describe(job, ctx) + "\n" + JobManager.tail(job.log, max).ifEmpty { "(no output yet)" })
            "wait" -> {
                val t = (args.int("timeout_seconds") ?: 120).coerceIn(1, 1800)
                val done = jobs.wait(id, t)
                if (done) jobs.markReported(job)
                ToolResult((if (done) "" else "still running after ${t}s\n") + describe(job, ctx) + "\n" + JobManager.tail(job.log, max))
            }
            "kill" -> if (jobs.kill(id)) ToolResult("stopping job #$id (${job.command})") else ToolResult("job #$id is not running (${describe(job, ctx)})")
            else -> ToolResult.error("action must be list, output, wait or kill")
        }
    }

    private fun describe(j: JobManager.Job, ctx: ToolContext): String {
        val state = when {
            j.running -> "running ${j.seconds}s"
            j.killed -> "killed after ${j.seconds}s"
            else -> "exited ${j.exitCode} after ${j.seconds}s"
        }
        return "#${j.id} [$state] ${j.command}  (log: ${ctx.workspace.relative(j.log)})"
    }
}
