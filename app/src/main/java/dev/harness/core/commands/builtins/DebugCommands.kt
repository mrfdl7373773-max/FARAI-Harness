package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.net.HarnessJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class PayloadCommand : Command {
    override val name = "payload"
    override val aliases = listOf("request")
    override val group = CommandGroup.DEBUG
    override val usage = "[share]"
    override val description = "Dump the raw JSON request sent on the last turn"
    override val subcommands = listOf("share")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val raw = ctx.state.lastRequestJson.ifEmpty { return CommandResult.Output("No request has been sent in this session yet.") }
        val pretty = runCatching {
            Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), HarnessJson.parseToJsonElement(raw))
        }.getOrDefault(raw)
        if (inv.arg(0) == "share") return CommandResult.Share("request.json", pretty)
        val shown = if (pretty.length > 40_000) pretty.take(40_000) + "\n… (truncated; use /payload share for all of it)" else pretty
        return CommandResult.Output("Last request · ${raw.length} bytes\n\n" + fence(shown, "json"))
    }
}

class HeadersCommand : Command {
    override val name = "headers"
    override val group = CommandGroup.DEBUG
    override val description = "Show the provider's HTTP response headers from the last turn"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val h = ctx.state.lastResponseHeaders
        if (h.isEmpty()) return CommandResult.Output("No response headers recorded yet.")
        return CommandResult.Output(fence(h.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key}: ${it.value}" }, "http"))
    }
}

class HealthCommand : Command {
    override val name = "health"
    override val aliases = listOf("ping")
    override val group = CommandGroup.DEBUG
    override val description = "Health-check the provider (GET /models, latency, model availability)"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult =
        CommandResult.Output(ctx.healthCheck())
}
