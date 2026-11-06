package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation

class ModelCommand : Command {
    override val name = "model"
    override val aliases = listOf("models")
    override val group = CommandGroup.MODEL
    override val usage = "[list | <model id>]"
    override val description = "Show the current model, list available models, or switch"
    override val subcommands = listOf("list")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val arg = inv.arg(0)
        val current = ctx.settings().provider.model
        if (arg == null) return CommandResult.Output("Current model: `$current`\n\nSwitch with `/model <id>`, or see what the provider offers with `/model list`.")
        if (arg == "list") {
            val models = ctx.listModels()
            if (models.isEmpty()) return CommandResult.Output("The provider returned no models.")
            return CommandResult.Output("**${models.size} models** (current marked ●)\n\n" + models.take(150).joinToString("\n") { if (it == current) "● `$it`" else "- `$it`" } +
                if (models.size > 150) "\n… and ${models.size - 150} more" else "")
        }
        val id = inv.rawArgs.trim()
        ctx.updateSettings { it.copy(provider = it.provider.copy(model = id)) }
        return CommandResult.Output("Model switched: `$current` → `$id`. The next message uses it.")
    }
}

class TempCommand : Command {
    override val name = "temp"
    override val aliases = listOf("temperature")
    override val group = CommandGroup.MODEL
    override val usage = "[0.0–2.0]"
    override val description = "Show or set the sampling temperature"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val arg = inv.arg(0) ?: return CommandResult.Output("Temperature: `${ctx.settings().provider.temperature}`")
        val t = arg.toFloatOrNull()?.takeIf { it in 0f..2f } ?: return CommandResult.Error("Temperature must be a number between 0 and 2")
        ctx.updateSettings { it.copy(provider = it.provider.copy(temperature = t)) }
        return CommandResult.Output("Temperature set to `$t`.")
    }
}

class TurnsCommand : Command {
    override val name = "turns"
    override val aliases = listOf("max-turns")
    override val group = CommandGroup.MODEL
    override val usage = "[1–200]"
    override val description = "Show or set the max agent turns per message"
    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val arg = inv.arg(0) ?: return CommandResult.Output("Max turns: `${ctx.settings().provider.maxTurns}`")
        val n = arg.toIntOrNull()?.takeIf { it in 1..200 } ?: return CommandResult.Error("Max turns must be a whole number from 1 to 200")
        ctx.updateSettings { it.copy(provider = it.provider.copy(maxTurns = n)) }
        return CommandResult.Output("Max turns set to `$n`.")
    }
}

class ReasoningCommand : Command {
    override val name = "reasoning"
    override val aliases = listOf("effort", "think")
    override val group = CommandGroup.MODEL
    override val usage = "[none | minimal | low | medium | high | xhigh | max | default]"
    override val description = "Show or set the model's reasoning effort"
    override val subcommands = dev.harness.net.ReasoningEffort.LEVELS + "default"

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val RE = dev.harness.net.ReasoningEffort
        // Accept "/reasoning high" and "/reasoning effort high".
        val words = inv.rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() != "effort" }
        val p = ctx.settings().provider
        val known = RE.levelsFor(p)
        val modelKnown = RE.modelLevels(p.model)
        val levelsLine = "Levels: " + RE.LEVELS.joinToString(", ") { "`$it`" } + ", or `default`."
        val hint = if (modelKnown != null) {
            if (modelKnown.isEmpty()) "`${p.model}` has no reasoning setting, so none is sent."
            else "`${p.model}` takes ${modelKnown.joinToString(", ") { "`$it`" }} (up to `${modelKnown.last()}`); other levels are sent as the nearest one."
        } else when (RE.dialect(p)) {
            dev.harness.net.ReasoningEffort.Dialect.OPENROUTER -> "OpenRouter maps the level to the nearest one the model supports."
            dev.harness.net.ReasoningEffort.Dialect.GEMINI -> "Gemini accepts ${known!!.joinToString(", ")}; higher levels are sent as `high`."
            dev.harness.net.ReasoningEffort.Dialect.ANTHROPIC -> "Claude accepts ${known!!.joinToString(", ")}."
            dev.harness.net.ReasoningEffort.Dialect.OPENAI -> "Each model takes a different set (many only low, medium, high). If the model rejects a level, the nearest one it accepts is used."
        }
        if (words.isEmpty()) {
            val now = RE.normalize(p.reasoningEffort)
            val sent = RE.forRequest(p)
            val current = when {
                now == null -> "`default` (the model decides)"
                sent != null && sent != now -> "`$now` (sent as `$sent` to this provider)"
                else -> "`$now`"
            }
            return CommandResult.Output("Reasoning effort: $current\n\n$levelsLine\n\n$hint")
        }
        val arg = words.first()
        if (arg.lowercase() in setOf("default", "auto", "reset", "clear")) {
            ctx.updateSettings { it.copy(provider = it.provider.copy(reasoningEffort = "")) }
            return CommandResult.Output("Reasoning effort reset to `default`. The model uses its own setting.")
        }
        val level = RE.normalize(arg) ?: return CommandResult.Error("Unknown level `$arg`. $levelsLine")
        ctx.updateSettings { it.copy(provider = it.provider.copy(reasoningEffort = level)) }
        val sent = known?.let { RE.nearest(level, it) }
        val note = when {
            known != null && known.isEmpty() -> " This model has no reasoning setting, so nothing is sent."
            sent != null && sent != level -> " This model doesn't take `$level`, so `$sent` is sent."
            else -> ""
        }
        return CommandResult.Output("Reasoning effort set to `$level`.$note The next message uses it.")
    }
}
