package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget
import dev.harness.core.commands.SessionSummary
import dev.harness.tools.TodoItem

class TodoCommand : Command {
    override val name = "todo"
    override val aliases = listOf("todos")
    override val group = CommandGroup.SESSION
    override val usage = "[clear]"
    override val description = "Show the session's todo list (or clear it)"
    override val subcommands = listOf("clear")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        if (inv.arg(0) == "clear") {
            ctx.state.todos.clear(); ctx.saveSession()
            return CommandResult.Output("Todo list cleared.")
        }
        val items = ctx.state.todos.items.value
        if (items.isEmpty()) return CommandResult.Output("No todo list yet. The agent creates one for multi-step tasks.")
        val done = items.count { it.status == TodoItem.DONE }
        return CommandResult.Output("**Todo · $done/${items.size} done**\n\n" + items.joinToString("\n") {
            val box = when (it.status) { TodoItem.DONE -> "☑"; TodoItem.IN_PROGRESS -> "◐"; else -> "☐" }
            "$box ${it.content}"
        })
    }
}

class ClearCommand : Command {
    override val name = "clear"
    override val group = CommandGroup.SESSION
    override val description = "Clear the conversation context (system prompt and todo list stay)"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val n = ctx.state.messages.size
        ctx.clearContext()
        return CommandResult.Output("Context cleared ($n messages removed).")
    }
}

class SaveCommand : Command {
    override val name = "save"
    override val group = CommandGroup.SESSION
    override val description = "Save this session now"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        ctx.saveSession()
        return CommandResult.Output("Saved “${ctx.sessionTitle()}”.")
    }
}

class ExportCommand : Command {
    override val name = "export"
    override val aliases = listOf("share")
    override val group = CommandGroup.SESSION
    override val description = "Share the session transcript as text"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult =
        CommandResult.Share(ctx.sessionTitle(), ctx.transcript())
}

class ResetAllCommand : Command {
    override val name = "reset-all"
    override val group = CommandGroup.SESSION
    override val description = "Wipe this session (messages, todos, mode, tool toggles); keep provider config"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        ctx.resetSession()
        return CommandResult.Output("Session wiped. Provider settings and prompts are unchanged.")
    }
}

class RenameCommand : Command {
    override val name = "rename"
    override val aliases = listOf("title")
    override val group = CommandGroup.SESSION
    override val usage = "<new title>"
    override val description = "Rename this session"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val t = inv.rawArgs.trim().trim('"')
        if (t.isEmpty()) return CommandResult.Error("Usage: /rename <new title>")
        ctx.renameSession(t)
        return CommandResult.Output("Renamed to “$t”.")
    }
}

class ForkCommand : Command {
    override val name = "fork"
    override val aliases = listOf("clone")
    override val group = CommandGroup.SESSION
    override val usage = "[message count]"
    override val description = "Copy this session (optionally only its first N messages) and open the copy"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val n = inv.arg(0)?.let { it.toIntOrNull() ?: return CommandResult.Error("Message count must be a number") }
        ctx.saveSession()
        val id = ctx.forkSession(n) ?: return CommandResult.Error("Could not fork this session.")
        return CommandResult.Navigate(NavTarget.Session(id), "Forked${if (n != null) " (first $n messages)" else ""}.")
    }
}

private fun renderSessions(list: List<SessionSummary>, empty: String): CommandResult =
    if (list.isEmpty()) CommandResult.Output(empty)
    else CommandResult.Output(list.take(20).joinToString("\n") { s ->
        "- **${s.title}** · ${s.messageCount} msgs · ${fmtTime(s.updatedAt)}" +
            (if (s.tags.isNotEmpty()) " · " + s.tags.joinToString(" ") { "#$it" } else "") + "\n  `${s.id.take(8)}`"
    } + "\n\nOpen one with `/sessions open <id prefix>`.")

class SessionsCommand : Command {
    override val name = "sessions"
    override val aliases = listOf("find")
    override val group = CommandGroup.SESSION
    override val usage = "[search text | open <id>]"
    override val description = "Search past sessions by title, tag or content, or open one"
    override val subcommands = listOf("open")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        if (inv.arg(0) == "open") {
            val prefix = inv.arg(1) ?: return CommandResult.Error("Usage: /sessions open <id prefix>")
            val match = ctx.searchSessions("").filter { it.id.startsWith(prefix) }
            return when (match.size) {
                0 -> CommandResult.Error("No session id starts with $prefix")
                1 -> { ctx.saveSession(); CommandResult.Navigate(NavTarget.Session(match.first().id)) }
                else -> CommandResult.Error("$prefix matches ${match.size} sessions; use more characters.")
            }
        }
        val q = inv.rawArgs.trim()
        if (q.isEmpty()) return CommandResult.Navigate(NavTarget.Sessions)
        return renderSessions(ctx.searchSessions(q), "No sessions match “$q”.")
    }
}

class TagCommand : Command {
    override val name = "tag"
    override val aliases = listOf("tags")
    override val group = CommandGroup.SESSION
    override val usage = "[add|remove|clear] [tags…]"
    override val description = "Show or change this session's tags"
    override val subcommands = listOf("add", "remove", "clear")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val current = ctx.sessionTags()
        val sub = inv.arg(0)
        val words = inv.args.drop(1).map { it.removePrefix("#") }
        val next = when (sub) {
            null -> return CommandResult.Output(if (current.isEmpty()) "No tags. Add some with `/tag add <tag>`." else current.joinToString(" ") { "#$it" })
            "add" -> current + words
            "remove", "rm" -> current - words.map { it.lowercase() }.toSet()
            "clear" -> emptyList()
            else -> current + inv.args.map { it.removePrefix("#") }
        }
        ctx.setSessionTags(next)
        val now = ctx.sessionTags()
        return CommandResult.Output(if (now.isEmpty()) "Tags cleared." else "Tags: " + now.joinToString(" ") { "#$it" })
    }
}

class HistoryCommand : Command {
    override val name = "history"
    override val group = CommandGroup.WORKFLOW
    override val usage = "[tag]"
    override val description = "List prior sessions sharing this session's tags (or a given tag)"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val tags = inv.arg(0)?.let { listOf(it.removePrefix("#").lowercase()) } ?: ctx.sessionTags()
        if (tags.isEmpty()) return CommandResult.Output("This session has no tags. Use `/history <tag>` or tag it with `/tag add`.")
        val list = tags.flatMap { ctx.sessionsByTag(it) }.distinctBy { it.id }.filter { it.id != ctx.state.id }
            .sortedByDescending { it.updatedAt }
        return renderSessions(list, "No other sessions tagged " + tags.joinToString(" ") { "#$it" } + ".")
    }
}
