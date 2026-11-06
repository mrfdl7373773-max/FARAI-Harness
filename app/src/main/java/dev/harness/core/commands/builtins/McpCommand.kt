package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget
import dev.harness.core.mcp.McpConfig
import dev.harness.core.mcp.McpStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class McpCommand : Command {
    override val name = "mcp"
    override val group = CommandGroup.TOOLS
    override val usage = "[list | connect [name] | disconnect <name> | auth [name] | logout <name> | tools [name] | add <name> <command…|url> | remove <name> | edit | prompts | prompt <server> <name> [key=value…] | resources]"
    override val description = "Model Context Protocol servers: status, connect, sign in (OAuth), add, tools, prompts"
    override val subcommands = listOf("list", "connect", "disconnect", "auth", "logout", "tools", "add", "remove", "edit", "prompts", "prompt", "resources")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val mcp = ctx.mcp ?: return CommandResult.Error("MCP is not available here.")
        return when (inv.arg(0)?.lowercase() ?: "list") {
            "list", "status" -> {
                val states = mcp.states.value.values.sortedBy { it.config.name }
                if (states.isEmpty()) {
                    // Nothing connected yet: show what is configured, if anything.
                    val cfg = runCatching { McpConfig.load(ctx.prompts.readOrNull("mcp.md"), ctx.workspaceRoot) }.getOrDefault(emptyList())
                    if (cfg.isEmpty()) return CommandResult.Output(EMPTY_HELP)
                    return CommandResult.Output("**MCP servers** (not started yet; they start with the next message or `/mcp connect`)\n\n" +
                        cfg.joinToString("\n") { "- **${it.name}** · ${it.transport.name.lowercase()} · `${it.summary.take(80)}`${if (!it.enabled) " · disabled" else ""}  \n  from `${it.source}`" })
                }
                CommandResult.Output("**MCP servers**\n\n" + states.joinToString("\n") { s ->
                    val badge = when (s.status) {
                        McpStatus.CONNECTED -> "🟢 connected"; McpStatus.CONNECTING -> "🟡 connecting"; McpStatus.NEEDS_AUTH -> "🔐 needs sign-in"
                        McpStatus.FAILED -> "🔴 failed"; McpStatus.DISABLED -> "⚪ disabled"
                    }
                    val signed = if (mcp.auth?.hasLogin(s.config) == true) " · signed in" else ""
                    "- **${s.config.name}** · $badge$signed${s.serverLabel?.let { " · $it" } ?: ""} · ${s.tools.size} tools" +
                        (if (s.prompts.isNotEmpty()) " · ${s.prompts.size} prompts" else "") + (if (s.hasResources) " · resources" else "") +
                        "  \n  `${s.config.summary.take(80)}` (from `${s.config.source}`)" + (s.error?.let { "\n  ⚠️ ${it.take(400)}" } ?: "")
                } + "\n\n`/mcp tools <name>` lists a server's tools. `/mcp edit` opens the config.")
            }
            "connect", "reconnect", "restart" -> {
                val failures = mcp.reconnect(inv.arg(1))
                val states = mcp.states.value.values
                val ok = states.filter { it.status == McpStatus.CONNECTED }
                CommandResult.Output(
                    (if (ok.isEmpty()) "No server connected." else "Connected: " + ok.joinToString { "**${it.config.name}** (${it.tools.size} tools)" }) +
                        (if (failures.isEmpty()) "" else "\n\n" + failures.joinToString("\n\n"))
                )
            }
            "disconnect", "stop" -> {
                val n = inv.arg(1) ?: return CommandResult.Error("Usage: /mcp disconnect <name>")
                mcp.disconnect(n)
                CommandResult.Output("Disconnected **$n** for now. It stays in the config; `/mcp connect $n` brings it back.")
            }
            "tools" -> {
                val n = inv.arg(1)
                val states = mcp.states.value.values.filter { n == null || it.config.name.equals(n, true) }
                val tools = states.flatMap { it.tools }
                if (tools.isEmpty()) return CommandResult.Output("No MCP tools${n?.let { " for $it" } ?: ""}. Connect with `/mcp connect`.")
                CommandResult.Output("**MCP tools** (${tools.size})\n\n" + tools.joinToString("\n") { t ->
                    "- `${t.exposedName}`${if (t.readOnly) " · read-only" else ""} — ${t.description.lineSequence().firstOrNull().orEmpty().take(140)}"
                } + "\n\nOverride a description with `prompts/tools/mcp/<tool>.md`; gate one with a rule like `${tools.first().server}_* ask` in `/permissions edit`.")
            }
            "auth", "login", "signin" -> {
                val names = inv.arg(1)?.let { listOf(it) } ?: mcp.needingAuth()
                if (names.isEmpty()) return CommandResult.Output("No server is waiting for a sign-in. Name one: `/mcp auth <name>`.")
                // One browser sign-in at a time: they share the loopback callback port.
                val lines = names.map { mcp.login(it) }
                CommandResult.Output(lines.joinToString("\n\n"))
            }
            "logout", "signout" -> {
                val n = inv.arg(1) ?: return CommandResult.Error("Usage: /mcp logout <name>")
                if (mcp.logout(n)) CommandResult.Output("Signed out of **$n** and forgot its tokens.")
                else CommandResult.Output("No saved sign-in for **$n**.")
            }
            "edit", "config" -> CommandResult.Navigate(NavTarget.PromptEditor("mcp.md"))
            "add" -> add(inv, ctx)
            "remove", "rm", "delete" -> remove(inv, ctx)
            "prompts" -> {
                val ps = mcp.states.value.values.flatMap { it.prompts }
                if (ps.isEmpty()) CommandResult.Output("No MCP prompts.")
                else CommandResult.Output("**MCP prompts**\n\n" + ps.joinToString("\n") { p ->
                    "- `/mcp prompt ${p.server} ${p.name}${p.arguments.joinToString("") { (a, req) -> if (req) " $a=…" else " [$a=…]" }}` — ${p.description.take(120)}"
                })
            }
            "prompt" -> {
                val server = inv.arg(1) ?: return CommandResult.Error("Usage: /mcp prompt <server> <name> [key=value…]")
                val pname = inv.arg(2) ?: return CommandResult.Error("Usage: /mcp prompt <server> <name> [key=value…]")
                val args = inv.args.drop(3).mapNotNull { a -> a.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                val text = runCatching { mcp.getPrompt(server, pname, args) }.getOrElse { return CommandResult.Error("Could not get the prompt: ${it.message}") }
                if (text.isBlank()) CommandResult.Error("The prompt came back empty.") else CommandResult.SendToAgent(text, "Sent MCP prompt **$server/$pname**.")
            }
            "resources" -> {
                val rs = mcp.listResources()
                if (rs.isEmpty()) CommandResult.Output("No MCP resources.")
                else CommandResult.Output("**MCP resources**\n\n" + rs.take(100).joinToString("\n") { "- **${it.server}** `${it.uri}` ${it.name}" } +
                    "\n\nThe agent reads them with the `mcp_resource` tool.")
            }
            else -> CommandResult.Error("Unknown sub-command. Try /help mcp.")
        }
    }

    /** `/mcp add name npx -y pkg …` or `/mcp add name https://…` writes an entry into prompts/mcp.md. */
    private suspend fun add(inv: Invocation, ctx: CommandContext): CommandResult {
        val name = inv.arg(1)?.replace(Regex("[^A-Za-z0-9_-]"), "-") ?: return CommandResult.Error("Usage: /mcp add <name> <command…> or /mcp add <name> <url>")
        val rest = inv.args.drop(2)
        if (rest.isEmpty()) return CommandResult.Error("Usage: /mcp add <name> <command…> or /mcp add <name> <url>")
        val entry: JsonObject = if (rest.size == 1 && (rest[0].startsWith("http://") || rest[0].startsWith("https://"))) {
            val type = if (rest[0].trimEnd('/').endsWith("/sse")) "sse" else "remote"
            JsonObject(mapOf("type" to JsonPrimitive(type), "url" to JsonPrimitive(rest[0]), "enabled" to JsonPrimitive(true)))
        } else {
            JsonObject(mapOf("type" to JsonPrimitive("local"), "command" to JsonArray(rest.map { JsonPrimitive(it) }), "enabled" to JsonPrimitive(true)))
        }
        val md = ctx.prompts.readOrNull("mcp.md") ?: DEFAULT_MD
        val updated = rewrite(md) { servers -> servers + (name to entry) }
        ctx.prompts.write("mcp.md", updated)
        val failures = ctx.mcp?.reconnect(name).orEmpty()
        val st = ctx.mcp?.states?.value?.get(name)
        return CommandResult.Output(
            "Added **$name** to `prompts/mcp.md`." +
                (if (st?.status == McpStatus.CONNECTED) " Connected: ${st.tools.size} tools." else "") +
                (if (failures.isNotEmpty()) "\n\n" + failures.joinToString("\n") else "") +
                "\n\nAdd headers (API keys) or environment variables with `/mcp edit`."
        )
    }

    private suspend fun remove(inv: Invocation, ctx: CommandContext): CommandResult {
        val name = inv.arg(1) ?: return CommandResult.Error("Usage: /mcp remove <name>")
        val md = ctx.prompts.readOrNull("mcp.md") ?: return CommandResult.Error("prompts/mcp.md does not exist.")
        var found = false
        val updated = rewrite(md) { servers -> servers.filterKeys { k -> (k != name).also { if (!it) found = true } } }
        if (!found) return CommandResult.Error("No server named $name in prompts/mcp.md (servers from workspace files are edited there).")
        ctx.prompts.write("mcp.md", updated)
        ctx.mcp?.disconnect(name)
        ctx.mcp?.ensureConnected()
        return CommandResult.Output("Removed **$name**.")
    }

    companion object {
        private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** Applies [change] to the servers in the first ```json block of mcp.md (adding one if needed). */
        fun rewrite(md: String, change: (Map<String, JsonElement>) -> Map<String, JsonElement>): String {
            val re = Regex("(?s)```(?:jsonc?)?\\s*\\n(.*?)```")
            val m = re.find(md)
            val root = m?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(McpConfig.stripJsonComments(it.groupValues[1])) as? JsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())
            val key = when { root["mcpServers"] is JsonObject -> "mcpServers"; else -> "mcp" }
            val servers = (root[key] as? JsonObject) ?: JsonObject(emptyMap())
            val newRoot = JsonObject(root + (key to JsonObject(change(servers))))
            val block = "```json\n" + pretty.encodeToString(JsonElement.serializer(), newRoot) + "\n```"
            return if (m != null) md.replaceRange(m.range, block) else md.trimEnd() + "\n\n" + block + "\n"
        }

        const val EMPTY_HELP = "No MCP servers yet.\n\n" +
            "- Local (runs in Alpine Linux): `/mcp add fs npx -y @modelcontextprotocol/server-filesystem /workspace`\n" +
            "- Remote: `/mcp add <name> <server url>`; if it needs an account, `/mcp auth <name>` signs you in through the browser\n\n" +
            "Or `/mcp edit` to write the config by hand (the `mcp` and `mcpServers` layouts both work). A project can carry its own servers in `.mcp.json`, `farai.json` or `.farai/mcp.json`."

        val DEFAULT_MD = "# MCP servers\n\n```json\n{\n  \"mcp\": {}\n}\n```\n"
    }
}
