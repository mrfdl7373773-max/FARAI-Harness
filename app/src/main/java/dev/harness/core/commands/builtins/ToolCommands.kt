package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.net.HarnessJson
import kotlinx.serialization.json.jsonObject

class ToolsCommand : Command {
    override val name = "tools"
    override val group = CommandGroup.TOOLS
    override val usage = "[name]"
    override val description = "List tools with the descriptions the model sees (from prompt files)"

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val s = ctx.settings()
        inv.arg(0)?.let { n ->
            val t = ctx.tools.get(n) ?: return CommandResult.Error("No tool named $n")
            val desc = runCatching { ctx.persona.toolDescription(t) }.getOrElse { "⚠️ ${it.message}" }
            return CommandResult.Output("**${t.name}** · `${t.promptPath}`\n\n$desc\n\nParameters:\n" + fence(t.parameters.toString(), "json"))
        }
        val sb = StringBuilder()
        for (t in ctx.tools.all) {
            val off = t.name in ctx.state.disabledTools || (t.name == "task" && !s.agent.subagents)
            val desc = runCatching { ctx.persona.toolDescription(t) }.getOrElse { "⚠️ prompt file missing" }
            val first = desc.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            sb.append("- `").append(t.name).append('`')
            if (off) sb.append(" *(disabled)*")
            sb.append(" — ").appendLine(first)
        }
        sb.append("\nEdit a description with `/prompt edit <tool>`; toggle with `/tool disable <name>`.")
        return CommandResult.Output(sb.toString())
    }
}

class ToolCommand : Command {
    override val name = "tool"
    override val group = CommandGroup.TOOLS
    override val usage = "<enable|disable|status> [name]"
    override val description = "Enable or disable a tool for this session only"
    override val subcommands = listOf("enable", "disable", "status")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val sub = inv.arg(0) ?: "status"
        if (sub == "status") {
            val off = ctx.state.disabledTools
            return CommandResult.Output(if (off.isEmpty()) "All tools enabled for this session." else "Disabled here: " + off.sorted().joinToString(", ") { "`$it`" })
        }
        val n = inv.arg(1) ?: return CommandResult.Error("Usage: /tool $sub <name>")
        if (ctx.tools.get(n) == null) return CommandResult.Error("No tool named $n. See /tools.")
        when (sub) {
            "disable", "off" -> ctx.state.disabledTools += n
            "enable", "on" -> ctx.state.disabledTools -= n
            else -> return CommandResult.Error("Use enable, disable or status")
        }
        ctx.saveSession()
        return CommandResult.Output("`$n` ${if (n in ctx.state.disabledTools) "disabled" else "enabled"} for this session.")
    }
}

class RunCommand : Command {
    override val name = "run"
    override val group = CommandGroup.TOOLS
    override val usage = "<tool> [json args]"
    override val description = "Run a tool yourself, outside the model loop (e.g. /run read {\"path\":\"a.py\"})"

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val n = inv.arg(0) ?: return CommandResult.Error("Usage: /run <tool> {json}")
        if (ctx.tools.get(n) == null) return CommandResult.Error("No tool named $n")
        val json = inv.rest(1).ifBlank { "{}" }
        runCatching { HarnessJson.parseToJsonElement(json).jsonObject }.getOrElse {
            return CommandResult.Error("Arguments must be a JSON object: ${it.message}")
        }
        val r = ctx.runTool(n, json)
        return CommandResult.Output("**$n** ${if (r.isError) "failed" else "ok"}\n\n" + fence(r.output.take(20_000)))
    }
}

class LastCommand : Command {
    override val name = "last"
    override val group = CommandGroup.TOOLS
    override val description = "Show the full output of the most recent tool call again"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val name = ctx.state.lastToolName ?: return CommandResult.Output("No tool has run in this session yet.")
        return CommandResult.Output("Last tool: **$name**\n\n" + fence(ctx.state.lastToolResult.orEmpty().take(30_000)))
    }
}
