package dev.harness.core.commands.builtins

import dev.harness.core.Goal
import dev.harness.core.PersonaLoader
import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget
import dev.harness.core.permission.PermissionRules

class AgentCommand : Command {
    override val name = "agent"
    override val aliases = listOf("mode", "agents")
    override val group = CommandGroup.SESSION
    override val usage = "[name | list | edit <name> | new <name>]"
    override val description = "Switch the agent (Build, Plan, Verify, or your own) or manage agent files"
    override val subcommands = listOf("list", "edit", "new", "build", "plan", "verify")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val store = ctx.agents ?: return CommandResult.Error("Agents are not available here.")
        val sub = inv.arg(0)?.lowercase()
        if (sub == null || sub == "list") {
            val all = store.all()
            val rows = all.joinToString("\n") { a ->
                val mark = if (a.name.equals(ctx.state.agent, true)) "▶ " else "- "
                "$mark**${a.name}** · ${a.kind.name.lowercase()}${if (a.hidden) " · hidden" else ""} — ${a.description.ifBlank { "(no description)" }}  \n  `${a.source}`"
            }
            return CommandResult.Output("**Agents** (active: **${ctx.state.agent}**)\n\n$rows\n\nSwitch with `/agent <name>`. Edit with `/agent edit <name>`; create with `/agent new <name>`. " +
                "Project agents live in `.farai/agents/*.md` (also `.claude/agents`).")
        }
        if (sub == "edit") {
            val n = inv.arg(1) ?: return CommandResult.Error("Usage: /agent edit <name>")
            val a = store.get(n) ?: return CommandResult.Error("No agent named $n.")
            if (!a.source.startsWith("prompts/")) return CommandResult.Output("${a.name} comes from the workspace file `${a.source}`; open it with the file viewer or ask the agent to edit it.")
            return CommandResult.Navigate(NavTarget.PromptEditor(a.source.removePrefix("prompts/")))
        }
        if (sub == "new") {
            val n = inv.arg(1)?.lowercase()?.replace(Regex("[^a-z0-9_-]"), "-")?.trim('-')
                ?: return CommandResult.Error("Usage: /agent new <name> [subagent]")
            if (store.get(n) != null) return CommandResult.Error("An agent named $n already exists.")
            val kind = if (inv.arg(2)?.startsWith("sub") == true) "subagent" else "primary"
            val path = "agents/$n.md"
            ctx.prompts.write(path, NEW_AGENT.replace("{{name}}", n).replace("{{mode}}", kind))
            return CommandResult.Navigate(NavTarget.PromptEditor(path), "Created **$n** ($kind). Describe its job, then save.")
        }
        val a = store.get(sub) ?: return CommandResult.Error("Unknown agent \"$sub\". Try /agent list.")
        if (!a.kind.canBePrimary) return CommandResult.Error("${a.name} is a subagent: the task tool starts it. Pick a primary agent (${store.primary().joinToString { it.name }}).")
        ctx.setAgent(a.name)
        return CommandResult.Output("Agent: **${a.label}**. ${a.description}")
    }

    companion object {
        val NEW_AGENT = """
            ---
            name: {{name}}
            description: One sentence on what this agent is for and when to use it.
            mode: {{mode}}
            tools: "*"
            # permission: |
            #   bash * ask
            ---
            You are the {{name}} agent. Describe the job, the steps to follow, what to avoid,
            and what the final answer should look like.
        """.trimIndent() + "\n"
    }
}

class GoalCommand : Command {
    override val name = "goal"
    override val group = CommandGroup.WORKFLOW
    override val usage = "<objective> | status | pause | resume | clear"
    override val description = "Keep working across turns until a reviewer confirms the objective is met"
    override val subcommands = listOf("status", "pause", "resume", "clear")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val store = ctx.goals ?: return CommandResult.Error("Goals are not available here.")
        val id = ctx.state.id
        val goal = store.get(id)
        return when (inv.arg(0)?.lowercase()) {
            null, "status" -> CommandResult.Output(
                goal?.let { "**Goal** (${it.status}, round ${it.rounds}): ${it.objective}" + (it.reason.takeIf { r -> r.isNotBlank() }?.let { r -> "\n\nLast check: $r" } ?: "") }
                    ?: "No goal. Start one with `/goal <objective>`: the agent keeps going until a separate reviewer call confirms it is done (max ${ctx.settings().agent.goalMaxRounds} rounds)."
            )
            "pause" -> goal?.let { store.set(id, it.copy(status = Goal.PAUSED, reason = "paused by you")); ctx.goalChanged(store.get(id)); CommandResult.Output("Goal paused.") }
                ?: CommandResult.Error("No goal to pause.")
            "clear", "stop", "cancel" -> { store.set(id, null); ctx.goalChanged(null); CommandResult.Output("Goal cleared.") }
            "resume" -> {
                val g = goal ?: return CommandResult.Error("No goal to resume.")
                val resumed = g.copy(status = Goal.ACTIVE, stalls = 0, rounds = 0)
                store.set(id, resumed)
                ctx.goalChanged(resumed)
                val text = ctx.persona.goalPrompt(PersonaLoader.GOAL_CONTINUE, g.objective, "The user resumed the goal.") ?: "Continue working toward: ${g.objective}"
                CommandResult.SendToAgent(text, "Goal resumed.")
            }
            else -> {
                val objective = inv.rawArgs.trim()
                if (objective.length > Goal.MAX_OBJECTIVE) return CommandResult.Error("Keep the objective under ${Goal.MAX_OBJECTIVE} characters.")
                val g = Goal(objective)
                store.set(id, g)
                ctx.goalChanged(g)
                val kickoff = ctx.persona.goalPrompt(PersonaLoader.GOAL_KICKOFF, objective) ?: objective
                CommandResult.SendToAgent(kickoff, "🎯 Goal set. The agent works until the reviewer confirms it (max ${ctx.settings().agent.goalMaxRounds} rounds). `/goal pause` to stop.")
            }
        }
    }
}

class InitCommand : Command {
    override val name = "init"
    override val group = CommandGroup.WORKFLOW
    override val usage = "[focus]"
    override val description = "Study the project and write an AGENTS.md so future sessions start smart"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        if ("init" !in ctx.persona.templateNames()) return CommandResult.Error("prompts/templates/init.md is missing. Restore it in Settings → Prompts.")
        return CommandResult.SendToAgent(ctx.persona.expandTemplate("init", inv.rawArgs.trim()), "Writing AGENTS.md for this workspace…")
    }
}

class ReviewCommand : Command {
    override val name = "review"
    override val group = CommandGroup.WORKFLOW
    override val usage = "[commit | branch | path]"
    override val description = "Review uncommitted changes (or a commit, branch or folder) for real bugs"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        if ("review" !in ctx.persona.templateNames()) return CommandResult.Error("prompts/templates/review.md is missing.")
        return CommandResult.SendToAgent(ctx.persona.expandTemplate("review", inv.rawArgs.trim()), null)
    }
}

class PermissionsCommand : Command {
    override val name = "permissions"
    override val aliases = listOf("perms")
    override val group = CommandGroup.TOOLS
    override val usage = "[edit | forget]"
    override val description = "Show the permission rules in effect, edit them, or forget \"always allow\" answers"
    override val subcommands = listOf("edit", "forget")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        when (inv.arg(0)?.lowercase()) {
            "edit" -> return CommandResult.Navigate(NavTarget.PromptEditor("permissions.md"))
            "forget", "reset" -> {
                val n = ctx.state.remembered.size
                ctx.state.remembered.clear()
                return CommandResult.Output("Forgot $n \"always allow\" answer${if (n == 1) "" else "s"} for this session.")
            }
        }
        val global = ctx.prompts.readOrNull("permissions.md")?.let { PermissionRules.parse(it, "permissions.md") }.orEmpty()
        val agent = ctx.agents?.resolvePrimary(ctx.state.agent)
        val remembered = ctx.state.remembered.map { it.replace('\u0000', ' ') }
        return CommandResult.Output(buildString {
            appendLine("**Permission rules** (last match wins; no match = allow)")
            appendLine()
            appendLine("From `permissions.md`:")
            appendLine(fence(global.joinToString("\n").ifEmpty { "(none)" }))
            if (agent != null) {
                appendLine("From the **${agent.name}** agent:")
                appendLine(fence(agent.permissions.joinToString("\n").ifEmpty { "(none)" }))
            }
            appendLine("Always-allowed this session:")
            appendLine(fence(remembered.joinToString("\n").ifEmpty { "(none)" }))
            append("Safety lists (Settings → Safety) still apply on top. `/permissions edit` to change rules, `/permissions forget` to clear the always-allowed list.")
        })
    }
}

class JobsCommand : Command {
    override val name = "jobs"
    override val group = CommandGroup.TOOLS
    override val usage = "[kill <id> | kill all]"
    override val description = "List background shell jobs, or stop them"
    override val subcommands = listOf("kill")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val jobs = ctx.jobs ?: return CommandResult.Error("Background jobs are not available here.")
        if (inv.arg(0) == "kill") {
            val target = inv.arg(1) ?: return CommandResult.Error("Usage: /jobs kill <id|all>")
            if (target == "all") { jobs.killSession(ctx.state.id); return CommandResult.Output("Stopping every running job in this session.") }
            val id = target.removePrefix("#").toIntOrNull() ?: return CommandResult.Error("Not a job id: $target")
            return if (jobs.kill(id)) CommandResult.Output("Stopping job #$id.") else CommandResult.Error("Job #$id is not running.")
        }
        val list = jobs.list(ctx.state.id)
        if (list.isEmpty()) return CommandResult.Output("No background jobs. The agent starts them with `bash` and `background: true`.")
        return CommandResult.Output("**Background jobs**\n\n" + list.joinToString("\n") { j ->
            val st = if (j.running) "running ${j.seconds}s" else if (j.killed) "stopped" else "exit ${j.exitCode}"
            "- **#${j.id}** [$st] `${j.command.take(80)}`"
        })
    }
}
