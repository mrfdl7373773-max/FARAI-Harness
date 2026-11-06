package dev.harness.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Some models cap output below the configured max_tokens and answer HTTP 400.
 * We read the cap from the error (the largest number below what we asked for) and retry once,
 * or drop the field so the provider uses its own default.
 */
object MaxTokensFallback {
    private val mention = Regex("max_tokens|max_completion_tokens|max_output_tokens|output tokens|completion tokens|maximum.{0,40}tokens", RegexOption.IGNORE_CASE)

    fun isMaxTokensError(body: String): Boolean = mention.containsMatchIn(body)

    fun requested(body: String): Int? = runCatching {
        HarnessJson.parseToJsonElement(body).jsonObject["max_tokens"]?.jsonPrimitive?.intOrNull
    }.getOrNull()

    /** Largest number in [error] that is a plausible cap below [asked], or null. */
    fun capFrom(error: String, asked: Int): Int? =
        Regex("\\d[\\d,]{2,}").findAll(error)
            .mapNotNull { it.value.replace(",", "").toIntOrNull() }
            .filter { it in 256 until asked }
            .maxOrNull()

    /** Returns [body] with max_tokens set to [value], or removed when [value] is null. */
    fun withMaxTokens(body: String, value: Int?): String {
        val obj = HarnessJson.parseToJsonElement(body).jsonObject
        val map = obj.toMutableMap()
        if (value == null) map.remove("max_tokens") else map["max_tokens"] = JsonPrimitive(value)
        return HarnessJson.encodeToString(JsonObject.serializer(), JsonObject(map))
    }
}
