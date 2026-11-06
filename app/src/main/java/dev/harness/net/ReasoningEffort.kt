package dev.harness.net

import dev.harness.data.settings.ProviderSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Reasoning effort across OpenAI-compatible providers.
 *
 * The ladder is the union every provider draws from (OpenAI: none, minimal, low, medium, high,
 * xhigh, max; Anthropic: low…max; Gemini: minimal…high). Each model accepts only part of it, so:
 * - OpenRouter gets `reasoning: {effort}` and maps it to what the model supports itself.
 * - Everyone else gets `reasoning_effort`; known limits are clamped up front (Gemini tops out at
 *   high), and when a provider still rejects the value the client retries once with the nearest
 *   level its error lists, or without the field.
 */
object ReasoningEffort {
    val LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    private val ALIASES = mapOf(
        "off" to "none", "disable" to "none", "disabled" to "none", "0" to "none",
        "min" to "minimal", "lo" to "low", "med" to "medium", "mid" to "medium", "normal" to "medium",
        "hi" to "high", "extra" to "xhigh", "extra-high" to "xhigh", "extrahigh" to "xhigh", "x-high" to "xhigh",
        "very-high" to "xhigh", "maximum" to "max", "ultra" to "max",
    )

    /** A level name from user input, or null when it is not one. */
    fun normalize(raw: String): String? {
        val k = raw.trim().lowercase().replace('_', '-')
        if (k.isEmpty()) return null
        return if (k in LEVELS) k else ALIASES[k]
    }

    enum class Dialect { OPENROUTER, GEMINI, ANTHROPIC, OPENAI }

    fun dialect(p: ProviderSettings): Dialect {
        val url = p.baseUrl.lowercase()
        return when {
            "openrouter.ai" in url -> Dialect.OPENROUTER
            "generativelanguage.googleapis.com" in url || "aiplatform.googleapis.com" in url -> Dialect.GEMINI
            "anthropic.com" in url -> Dialect.ANTHROPIC
            else -> Dialect.OPENAI
        }
    }

    /** What this provider is known to accept, or null when it varies by model (let the API decide). */
    fun knownLevels(p: ProviderSettings): List<String>? = when (dialect(p)) {
        Dialect.OPENROUTER -> null
        Dialect.GEMINI -> if ("2.5" in p.model) listOf("none", "minimal", "low", "medium", "high") else listOf("minimal", "low", "medium", "high")
        Dialect.ANTHROPIC -> listOf("low", "medium", "high", "xhigh", "max")
        Dialect.OPENAI -> null
    }

    /**
     * Levels this model is known to take, lowest to highest, read from its id (a vendor prefix like
     * "openai/" and a ":free" style suffix are ignored). An empty list means the model has no
     * reasoning setting; null means the model is unknown, so the UI falls back to Low/Medium/High.
     */
    fun modelLevels(model: String): List<String>? {
        val id = model.trim().lowercase().substringAfterLast('/').substringBefore(':')
        if (id.isEmpty()) return null
        fun has(vararg parts: String) = parts.any { it in id }
        val lmh = listOf("low", "medium", "high")
        return when {
            // OpenAI
            has("gpt-5") && has("-chat") -> emptyList()
            has("gpt-5") -> {
                val v = Regex("gpt-5\\.(\\d+)").find(id)?.groupValues?.get(1)?.let { 5 + it.toDouble() / 10.0 } ?: 5.0
                when {
                    id.endsWith("-max") -> listOf("low", "medium", "high", "xhigh")
                    v >= 5.2 -> listOf("none", "low", "medium", "high", "xhigh")
                    v >= 5.1 -> listOf("none", "low", "medium", "high")
                    else -> listOf("minimal", "low", "medium", "high")
                }
            }
            Regex("^o[1-9](-|$)").containsMatchIn(id) -> lmh
            has("gpt-4", "gpt-3.5", "chatgpt-4o") -> emptyList()
            // Anthropic
            has("claude") -> {
                val v = Regex("(\\d+)[.-](\\d)(?!\\d)").find(id.substringAfter("claude"))?.let { "${it.groupValues[1]}.${it.groupValues[2]}".toDoubleOrNull() }
                    ?: Regex("-(\\d)(?:-|$)").find(id.substringAfter("claude"))?.groupValues?.get(1)?.toDoubleOrNull()
                when {
                    v == null -> null
                    v < 3.7 -> emptyList()
                    has("opus") && v >= 4.7 -> listOf("low", "medium", "high", "xhigh", "max")
                    v >= 4.6 && has("opus", "sonnet") -> listOf("low", "medium", "high", "max")
                    else -> lmh
                }
            }
            // Google
            has("gemini-3") && has("flash") -> listOf("minimal", "low", "medium", "high")
            has("gemini-3.1") -> lmh
            has("gemini-3") -> listOf("low", "high")
            has("gemini-2.5") && has("pro") -> lmh
            has("gemini-2.5") -> listOf("none", "low", "medium", "high")
            has("gemini-2.0", "gemini-1.5", "gemma") -> emptyList()
            // xAI
            has("grok-3-mini") -> listOf("low", "high")
            else -> null
        }
    }

    /** Levels to offer for these settings: the model's own, else what the provider takes, else null. */
    fun levelsFor(p: ProviderSettings): List<String>? = modelLevels(p.model) ?: knownLevels(p)

    /** The effort to send for these settings, already clamped to what the model or provider is known to take. */
    fun forRequest(p: ProviderSettings): String? {
        val e = normalize(p.reasoningEffort) ?: return null
        val known = levelsFor(p) ?: return e
        if (known.isEmpty()) return null
        return nearest(e, known)
    }

    /** Short label for a level, as the UI shows it. */
    fun label(level: String): String = when (level) {
        "none" -> "Off"
        "minimal" -> "Minimal"
        "xhigh" -> "XHigh"
        else -> level.replaceFirstChar { it.uppercase() }
    }

    /** Adds the effort to an encoded request in this provider's shape. */
    fun apply(body: String, p: ProviderSettings, effort: String?): String {
        if (effort == null) return body
        val obj = runCatching { HarnessJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body
        val out = buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            if (dialect(p) == Dialect.OPENROUTER) put("reasoning", buildJsonObject { put("effort", JsonPrimitive(effort)) })
            else put("reasoning_effort", JsonPrimitive(effort))
        }
        return HarnessJson.encodeToString(JsonObject.serializer(), out)
    }

    /** Nearest level in [allowed] (ties go to the higher one); null when nothing is allowed. */
    fun nearest(effort: String, allowed: List<String>): String? {
        var list = allowed.mapNotNull { normalize(it) }.distinct()
        if (list.isEmpty()) return null
        if (effort in list) return effort
        // Asking for some reasoning never silently turns it off.
        if (effort != "none" && list.any { it != "none" }) list = list.filter { it != "none" }
        val i = LEVELS.indexOf(effort)
        return list.minWithOrNull(compareBy<String>({ kotlin.math.abs(LEVELS.indexOf(it) - i) }, { -LEVELS.indexOf(it) }))
    }

    fun isEffortError(body: String): Boolean {
        val b = body.lowercase()
        return "reasoning" in b || "effort" in b || "thinking" in b
    }

    /** Levels an error message lists as valid, e.g. "Valid values are: high, low, medium, none". */
    fun allowedFrom(body: String): List<String> {
        val b = body.lowercase()
        val idx = listOf("valid values", "supported values", "allowed values", "expected one of", "must be one of", "one of")
            .map { b.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return emptyList()
        val tail = b.substring(idx).take(240)
        return Regex("[a-z-]+").findAll(tail).map { it.value }.filter { it in LEVELS }.distinct().toList()
    }
}
