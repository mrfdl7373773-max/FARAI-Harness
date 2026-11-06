package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget

class TemplateCommand : Command {
    override val name = "template"
    override val aliases = listOf("tpl")
    override val group = CommandGroup.WORKFLOW
    override val usage = "<list | run <name> [details] | edit <name>>"
    override val description = "Start a task from a named template in prompts/templates/"
    override val subcommands = listOf("list", "run", "edit")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val names = ctx.persona.templateNames()
        return when (val sub = inv.arg(0) ?: "list") {
            "list" -> CommandResult.Output(
                if (names.isEmpty()) "No templates. Add .md files under prompts/templates/."
                else "**Templates**\n\n" + names.joinToString("\n") { "- `$it`" } + "\n\nRun with `/template run <name> <details>`."
            )
            "edit" -> {
                val n = inv.arg(1) ?: return CommandResult.Error("Usage: /template edit <name>")
                if (n !in names) return CommandResult.Error("No template named $n")
                CommandResult.Navigate(NavTarget.PromptEditor("templates/$n.md"))
            }
            "run" -> {
                val n = inv.arg(1) ?: return CommandResult.Error("Usage: /template run <name> [details]")
                runTemplate(n, inv.rest(2), names, ctx)
            }
            else -> if (sub in names) runTemplate(sub, inv.rest(1), names, ctx)
                else CommandResult.Error("Unknown template or sub-command \"$sub\". Try /template list.")
        }
    }

    private suspend fun runTemplate(n: String, details: String, names: List<String>, ctx: CommandContext): CommandResult {
        if (n !in names) return CommandResult.Error("No template named $n. Try /template list.")
        val text = ctx.persona.expandTemplate(n, details)
        ctx.setSessionTags(ctx.sessionTags() + "tpl-$n")
        return CommandResult.SendToAgent(text, "Started template **$n** (tagged #tpl-$n; see /history).")
    }
}

class RetryCommand : Command {
    override val name = "retry"
    override val aliases = listOf("regen")
    override val group = CommandGroup.WORKFLOW
    override val description = "Discard the last reply and ask the model again"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult =
        if (ctx.retryLast()) CommandResult.None else CommandResult.Error("Nothing to retry yet.")
}

class UndoCommand : Command {
    override val name = "undo"
    override val group = CommandGroup.WORKFLOW
    override val description = "Remove your last message and everything after it, and put back files the agent changed"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val n = ctx.undoLast()
        if (n == 0) return CommandResult.Output("Nothing to undo.")
        val files = ctx.lastUndoRestored()
        val fileNote = if (files.isEmpty()) "" else "\n\nRestored ${files.size} file${if (files.size == 1) "" else "s"}:\n" +
            files.take(20).joinToString("\n") { "- `$it`" } + if (files.size > 20) "\n- …" else ""
        return CommandResult.Output("Removed the last exchange ($n messages).$fileNote\n\n(Changes made through bash are not tracked.)")
    }
}
