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

    /** The effort to send for these settings, already clamped to what the provider is known to take. */
    fun forRequest(p: ProviderSettings): String? {
        val e = normalize(p.reasoningEffort) ?: return null
        val known = knownLevels(p) ?: return e
        return nearest(e, known)
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
