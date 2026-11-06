package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget
import dev.harness.core.memory.MemoryException
import dev.harness.core.memory.MemoryStore
import dev.harness.core.memory.RecallStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** /memory: see, search, edit and manage long-term memory. */
class MemoryCommand : Command {
    override val name = "memory"
    override val aliases = listOf("mem")
    override val group = CommandGroup.CONTEXT
    override val usage = "[show|view <path>|open <path>|search <words>|history [path]|revert <id>|reflect [all]|init [notes]|forget <what>|on|off|every <n>|path]"
    override val description = "Long-term memory: what FARAI remembers, search, history, reflection"
    override val subcommands = listOf("show", "view", "open", "search", "history", "revert", "reflect", "init", "forget", "on", "off", "every", "path")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult = withContext(Dispatchers.IO) {
        val rt = ctx.memory ?: return@withContext CommandResult.Error("Memory isn't available here.")
        val store = rt.store
        store.ensureSeeded()
        val sub = inv.arg(0)?.lowercase() ?: "show"
        try {
            when (sub) {
                "show", "status", "ls", "list" -> {
                    val s = ctx.settings().agent
                    CommandResult.Output(buildString {
                        appendLine("**Memory** ${if (s.memory) "on" else "off"} · reflection ${when { s.reflectEvery > 0 -> "every ${s.reflectEvery} messages + before compaction"; s.reflectEvery == 0 -> "before compaction only"; else -> "off" }}")
                        appendLine()
                        val core = store.coreBlocks()
                        appendLine("**Core** (${store.coreTotal()}/${store.limits.coreTotal} chars, in every prompt)")
                        core.forEach { b -> appendLine("- `${b.path}` ${bar(b.chars, b.limit)} ${b.chars}/${b.limit} — ${b.description.ifBlank { "(no description)" }}") }
                        val dirs = store.deferredDirs()
                        if (dirs.isNotEmpty()) {
                            appendLine(); appendLine("**Folders** (opened on demand)")
                            dirs.forEach { d -> appendLine("- `${d.path}` ${d.files} files — ${d.summary ?: ""}") }
                        }
                        val proj = store.allFiles().count { it.scope == MemoryStore.Scope.PROJECT }
                        if (proj > 0) { appendLine(); appendLine("**Project memory**: $proj files in `.farai/memory` (`/memory view project/`)") }
                        val last = store.history(limit = 3)
                        if (last.isNotEmpty()) {
                            appendLine(); appendLine("**Latest changes**")
                            last.forEach { c -> appendLine("- `${c.id}` ${c.op} `${c.path}` · ${RecallStore.fmt(c.time)}${c.reason?.let { " — ${it.take(90)}" } ?: ""}") }
                        }
                        appendLine()
                        append("`/memory view <path>` to read · `/memory search <words>` · `/memory history` · `/memory reflect` · Settings → Memory to browse and edit.")
                    })
                }
                "view", "cat", "read" -> CommandResult.Output(fence(store.read(inv.rest(1).ifBlank { "" }), "markdown"))
                "open", "edit" -> {
                    val r = store.resolve(inv.rest(1).ifBlank { MemoryStore.INDEX })
                    if (!r.file.exists()) return@withContext CommandResult.Error("No memory file at `${r.display}`.")
                    CommandResult.Navigate(NavTarget.OpenFile(r.file.absolutePath))
                }
                "search", "find", "grep" -> {
                    val q = inv.rest(1).ifBlank { return@withContext CommandResult.Error("Usage: /memory search <words>") }
                    val hits = store.search(q, 15)
                    val past = rt.recall.search(q, 5)
                    CommandResult.Output(buildString {
                        appendLine("**In memory**")
                        if (hits.isEmpty()) appendLine("Nothing.") else hits.forEach { appendLine("- `${it.path}:${it.line}` ${it.snippet}") }
                        appendLine(); appendLine("**In past conversations**")
                        if (past.isEmpty()) append("Nothing.") else past.forEach { h ->
                            appendLine("- \"${h.unit.title.take(50)}\" · ${h.unit.role}${h.unit.date?.let { " · $it" } ?: ""}: ${h.unit.text.replace('\n', ' ').take(160)}")
                        }
                    }.trim())
                }
                "history", "log" -> {
                    val list = store.history(inv.arg(1), 25)
                    if (list.isEmpty()) CommandResult.Output("No memory changes yet.") else CommandResult.Output(buildString {
                        appendLine("**Memory history** (newest first; `/memory revert <id>` undoes one)")
                        appendLine()
                        list.forEach { c -> appendLine("- `${c.id}` **${c.op}** `${c.path}` · ${RecallStore.fmt(c.time)}${c.source?.let { s -> if (s.startsWith("reflection")) " · 🔮 reflection" else "" } ?: ""}${c.reason?.let { "\n  ${it.take(200)}" } ?: ""}") }
                    }.trim())
                }
                "revert", "undo" -> {
                    val id = inv.arg(1) ?: return@withContext CommandResult.Error("Usage: /memory revert <id> (ids are in /memory history)")
                    CommandResult.Output(store.revert(id, "user"))
                }
                "reflect", "dream" -> {
                    rt.reflectNow(ctx.state, ctx.sessionTitle(), all = inv.arg(1)?.lowercase() == "all")
                    CommandResult.Output("🔮 Reflecting on this session in the background. You'll see a note when memory changes.")
                }
                "init", "setup" -> CommandResult.SendToAgent(ctx.persona.expandTemplate("memory-init", inv.rest(1)), note = "Setting up memory")
                "forget" -> {
                    val what = inv.rest(1).ifBlank { return@withContext CommandResult.Error("Usage: /memory forget <what to forget>") }
                    CommandResult.SendToAgent("Forget this everywhere in your memory: $what\nSearch memory for every copy, remove or rewrite each one (reason: the user asked to forget it), and confirm in one line what you removed.", note = "Forgetting")
                }
                "on", "off" -> {
                    val on = sub == "on"
                    ctx.updateSettings { it.copy(agent = it.agent.copy(memory = on)) }
                    CommandResult.Output(if (on) "Memory on. FARAI will remember across sessions." else "Memory off. Nothing is read or written until you turn it back on (files are kept).")
                }
                "every", "reflection" -> {
                    val n = inv.arg(1)?.lowercase()?.let { if (it == "off" || it == "never") -1 else it.toIntOrNull() }
                        ?: return@withContext CommandResult.Error("Usage: /memory every <n|0|off> — reflect every n user messages (0 = only before compaction).")
                    ctx.updateSettings { it.copy(agent = it.agent.copy(reflectEvery = n.coerceIn(-1, 100))) }
                    CommandResult.Output(when { n > 0 -> "Reflection every $n messages and before compaction."; n == 0 -> "Reflection only before compaction."; else -> "Background reflection off." })
                }
                "path", "where" -> CommandResult.Output("Agent memory: `${store.root.absolutePath}`\nProject memory: `${store.projectRoot?.absolutePath ?: "(no workspace)"}`")
                else -> CommandResult.Error("Unknown subcommand '$sub'. Usage: /memory $usage")
            }
        } catch (e: MemoryException) {
            CommandResult.Error(e.message ?: "memory error")
        }
    }

    private fun bar(used: Int, limit: Int): String {
        val n = ((used.toDouble() / limit.coerceAtLeast(1)) * 8).toInt().coerceIn(0, 8)
        return "▰".repeat(n) + "▱".repeat(8 - n)
    }
}

/** /remember <text>: asks the agent to save something to memory the right way. */
class RememberCommand : Command {
    override val name = "remember"
    override val group = CommandGroup.CONTEXT
    override val usage = "<what to remember>"
    override val description = "Tell FARAI something to keep in long-term memory"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val text = inv.rawArgs.trim().ifBlank { return CommandResult.Error("Usage: /remember <what to remember>") }
        return CommandResult.SendToAgent(
            "Remember this for future conversations: $text\n\nSave it with the memory tool in the right place (update an existing line if one covers it; keep my wording's certainty), then confirm in one short line.",
            note = "Remembering",
        )
    }
}

/** /recall <words>: searches past conversations directly, without the model. */
class RecallCommand : Command {
    override val name = "recall"
    override val group = CommandGroup.CONTEXT
    override val usage = "<words>"
    override val description = "Search everything said in past sessions"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult = withContext(Dispatchers.IO) {
        val rt = ctx.memory ?: return@withContext CommandResult.Error("Recall isn't available here.")
        val q = inv.rawArgs.trim().ifBlank { return@withContext CommandResult.Error("Usage: /recall <words>") }
        val out = rt.runRecallTool(buildJsonObject { put("query", JsonPrimitive(q)); put("limit", JsonPrimitive(10)); put("include_current", JsonPrimitive(true)) }, null)
        CommandResult.Output(fence(out, "text"))
    }
}
