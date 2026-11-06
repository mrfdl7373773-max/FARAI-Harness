package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.net.Role

class TokensCommand : Command {
    override val name = "tokens"
    override val aliases = listOf("usage")
    override val group = CommandGroup.CONTEXT
    override val description = "Estimate context size against the window and compaction threshold"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val s = ctx.settings()
        val est = ctx.estimateTokens()
        val window = s.provider.contextWindow
        val threshold = (window * s.agent.contextThreshold).toInt()
        val pct = if (window > 0) est * 100.0 / window else 0.0
        val usage = ctx.state.lastUsage?.let { "\nLast reply (provider-reported): ${it.promptTokens} prompt + ${it.completionTokens} completion tokens" } ?: ""
        val counts = ctx.state.messages.groupingBy { it.role }.eachCount()
        return CommandResult.Output(
            "≈ **$est** tokens of $window (%.1f%%)\nCompaction at $threshold tokens (${(s.agent.contextThreshold * 100).toInt()}%%) · ${if (s.agent.compaction) "on" else "off"}\n".format(pct) +
                "Messages: ${counts[Role.USER] ?: 0} user · ${counts[Role.ASSISTANT] ?: 0} assistant · ${counts[Role.TOOL] ?: 0} tool$usage"
        )
    }
}

class CompactCommand : Command {
    override val name = "compact"
    override val group = CommandGroup.CONTEXT
    override val description = "Summarise older turns now using compaction.md"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val before = ctx.estimateTokens()
        val summary = ctx.compactNow() ?: return CommandResult.Output("Nothing to compact yet; the conversation is short.")
        val after = ctx.estimateTokens()
        return CommandResult.Output("Compacted ≈$before → ≈$after tokens.\n\n$summary")
    }
}

class ContextCommand : Command {
    override val name = "context"
    override val aliases = listOf("system")
    override val group = CommandGroup.CONTEXT
    override val description = "Show the exact system message the next request will send"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val text = ctx.systemMessagePreview()
        return CommandResult.Output("System message · ${text.length} chars\n\n" + fence(text, "markdown"))
    }
}
