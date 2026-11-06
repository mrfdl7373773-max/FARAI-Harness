package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget

class HelpCommand : Command {
    override val name = "help"
    override val aliases = listOf("?", "commands")
    override val group = CommandGroup.GENERAL
    override val usage = "[command]"
    override val description = "Show all commands, or details for one"

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        inv.arg(0)?.let { n ->
            val c = ctx.registry.resolve(n) ?: return CommandResult.Error("No command named /$n")
            val alias = if (c.aliases.isNotEmpty()) "\nAliases: " + c.aliases.joinToString(", ") { "/$it" } else ""
            val subs = if (c.subcommands.isNotEmpty()) "\nSub-commands: " + c.subcommands.joinToString(", ") else ""
            return CommandResult.Output("**/${c.name}** ${c.usage}\n\n${c.description}$alias$subs")
        }
        val sb = StringBuilder()
        for ((group, cmds) in ctx.registry.grouped()) {
            sb.append("**").append(group.label).appendLine("**")
            for (c in cmds) {
                sb.append("- `/").append(c.name)
                if (c.usage.isNotEmpty()) sb.append(' ').append(c.usage)
                sb.append("` — ").appendLine(c.description)
            }
            sb.appendLine()
        }
        sb.append("Type `/help <command>` for details.")
        return CommandResult.Output(sb.toString())
    }
}

class ConfigCommand : Command {
    override val name = "config"
    override val aliases = listOf("provider")
    override val group = CommandGroup.GENERAL
    override val description = "Open Settings → Provider"
    override suspend fun execute(inv: Invocation, ctx: CommandContext) = CommandResult.Navigate(NavTarget.Provider)
}

class QuitCommand : Command {
    override val name = "quit"
    override val aliases = listOf("exit", "q")
    override val group = CommandGroup.GENERAL
    override val description = "Save and close this session, return to Sessions"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        ctx.saveSession()
        return CommandResult.Navigate(NavTarget.Sessions)
    }
}

class PromptCommand : Command {
    override val name = "prompt"
    override val aliases = listOf("prompts", "p")
    override val group = CommandGroup.PROMPTS
    override val usage = "<list|show|edit|diff|reset|history|rollback|export|path> [name]"
    override val description = "Manage the editable prompt files"
    override val subcommands = listOf("list", "show", "edit", "diff", "reset", "history", "rollback", "export", "path")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val sub = inv.arg(0)?.lowercase() ?: "list"
        val repo = ctx.prompts
        fun resolve(): String? = inv.arg(1)?.let { repo.resolveName(it) }
        fun needName(): CommandResult.Error =
            CommandResult.Error(if (inv.arg(1) == null) "Usage: /prompt $sub <name>" else "No prompt named \"${inv.arg(1)}\". Try /prompt list.")

        return when (sub) {
            "list", "ls" -> {
                val sb = StringBuilder()
                repo.list().groupBy { it.group }.toSortedMap(compareBy { it.order }).forEach { (g, files) ->
                    sb.append("**").append(g.label).appendLine("**")
                    files.forEach { f ->
                        val flag = when {
                            !f.exists -> " ⚠️ missing"
                            !f.hasDefault -> " · custom"
                            f.modified -> " · edited"
                            else -> ""
                        }
                        sb.appendLine("- `${f.path}` ${f.sizeBytes} B · ${if (f.lastModified > 0) fmtTime(f.lastModified) else "—"}$flag")
                    }
                    sb.appendLine()
                }
                CommandResult.Output(sb.toString().trimEnd())
            }
            "show", "cat" -> {
                val p = resolve() ?: return needName()
                val text = repo.readOrNull(p) ?: return CommandResult.Error("$p is missing. Use /prompt reset ${inv.arg(1)} to restore it.")
                CommandResult.Output("`$p`\n\n" + fence(text, "markdown"))
            }
            "edit", "open" -> {
                val p = resolve() ?: return needName()
                CommandResult.Navigate(NavTarget.PromptEditor(p))
            }
            "diff" -> {
                val p = resolve() ?: return needName()
                if (repo.defaultContent(p) == null) return CommandResult.Error("$p is a custom prompt; it has no shipped default to compare with.")
                val d = repo.unifiedDiff(p)
                if (d.isEmpty()) CommandResult.Output("`$p` is identical to the default.")
                else CommandResult.Output("`$p` vs default\n\n" + fence(d, "diff"))
            }
            "reset" -> {
                val p = resolve() ?: return needName()
                if (repo.reset(p)) CommandResult.Output("Restored `$p` from defaults. The previous version is in its history.")
                else CommandResult.Error("$p has no shipped default.")
            }
            "history" -> {
                val p = resolve() ?: return needName()
                val h = repo.historyOf(p)
                if (h.isEmpty()) return CommandResult.Output("No saved versions of `$p` yet.")
                CommandResult.Output("Versions of `$p` (newest first):\n\n" +
                    h.take(20).joinToString("\n") { "- `${it.id}` · ${fmtTime(it.timestamp)} · ${it.sizeBytes} B" } +
                    "\n\nRoll back with `/prompt rollback ${inv.arg(1)} <id>`.")
            }
            "rollback" -> {
                val p = resolve() ?: return needName()
                val id = inv.arg(2) ?: repo.historyOf(p).getOrNull(1)?.id
                    ?: return CommandResult.Error("Usage: /prompt rollback <name> <version id>")
                if (repo.rollback(p, id)) CommandResult.Output("Rolled `$p` back to version `$id`.")
                else CommandResult.Error("No version `$id` for $p.")
            }
            "export" -> {
                val p = resolve() ?: return needName()
                val text = repo.readOrNull(p) ?: return CommandResult.Error("$p is missing.")
                CommandResult.Share(p.substringAfterLast('/'), text)
            }
            "path" -> {
                val p = resolve() ?: return needName()
                CommandResult.Output("`${repo.fileFor(p).absolutePath}`")
            }
            else -> CommandResult.Error("Unknown sub-command \"$sub\". $usage")
        }
    }
}
