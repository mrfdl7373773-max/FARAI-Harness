package dev.harness.core.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One field of an elicitation form (MCP restricts the schema to flat primitives). */
data class ElicitField(
    val key: String,
    val title: String,
    val description: String,
    /** string, number, integer, boolean, enum, or multi (array of enum values). */
    val kind: String,
    val required: Boolean,
    val options: List<Pair<String, String>> = emptyList(),
    val default: String? = null,
    val format: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
)

/** Something an MCP server is waiting on the user for. The UI shows the first one in the list. */
sealed class McpInteraction {
    abstract val id: Long
    abstract val server: String

    /** elicitation/create (form): fill in fields, or decline / cancel. */
    data class Form(
        override val id: Long,
        override val server: String,
        val message: String,
        val fields: List<ElicitField>,
        /** Accepted values by key (typed), or null for decline (false) / cancel (null in [action]). */
        val answer: CompletableDeferred<ElicitAnswer>,
    ) : McpInteraction()

    /** elicitation/create (url): open a page the server needs the user to visit. */
    data class Url(
        override val id: Long,
        override val server: String,
        val message: String,
        val url: String,
        val answer: CompletableDeferred<ElicitAnswer>,
    ) : McpInteraction()

    /** sampling/createMessage: the server wants to run a prompt on the user's model. */
    data class Sampling(
        override val id: Long,
        override val server: String,
        val systemPrompt: String?,
        val preview: String,
        val maxTokens: Int?,
        val answer: CompletableDeferred<SamplingDecision>,
    ) : McpInteraction()
}

data class ElicitAnswer(val action: String, val content: Map<String, JsonElement> = emptyMap()) {
    companion object {
        val DECLINE = ElicitAnswer("decline")
        val CANCEL = ElicitAnswer("cancel")
    }
}

enum class SamplingDecision { DENY, ONCE, ALWAYS }

/** A sampling request in plain terms for the app's own chat client. */
data class SamplingRequest(
    val server: String,
    val systemPrompt: String?,
    /** role to text */
    val messages: List<Pair<String, String>>,
    val maxTokens: Int?,
    val temperature: Float?,
    val stopSequences: List<String>,
    val modelHints: List<String>,
)

data class SamplingResponse(val text: String, val model: String, val stopReason: String)

object Elicitation {
    /** Reads requestedSchema into fields; unknown shapes fall back to a text field. */
    fun fields(schema: JsonObject?): List<ElicitField> {
        val props = schema?.get("properties") as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        return props.mapNotNull { (key, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            val type = s("type") ?: "string"
            val enumValues = (o["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val enumNames = (o["enumNames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            // 2025-11-25 titled enums: oneOf/anyOf [{const, title}]
            val titled = ((o["oneOf"] ?: o["anyOf"]) as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.let { eo -> (eo["const"] as? JsonPrimitive)?.contentOrNull?.let { c -> c to ((eo["title"] as? JsonPrimitive)?.contentOrNull ?: c) } }
            }
            val items = o["items"] as? JsonObject
            val itemEnum = (items?.get("enum") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.map { it to it }
                ?: ((items?.get("anyOf") ?: items?.get("oneOf")) as? JsonArray)?.mapNotNull { e ->
                    (e as? JsonObject)?.let { eo -> (eo["const"] as? JsonPrimitive)?.contentOrNull?.let { c -> c to ((eo["title"] as? JsonPrimitive)?.contentOrNull ?: c) } }
                }
            val options = when {
                type == "array" && itemEnum != null -> itemEnum
                titled != null && titled.isNotEmpty() -> titled
                enumValues != null -> enumValues.mapIndexed { i, e -> e to (enumNames?.getOrNull(i) ?: e) }
                else -> emptyList()
            }
            val kind = when {
                type == "array" -> "multi"
                options.isNotEmpty() -> "enum"
                type in setOf("number", "integer", "boolean") -> type
                else -> "string"
            }
            val def = o["default"]?.let { el ->
                when (el) { is JsonPrimitive -> el.contentOrNull; is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(","); else -> null }
            }
            ElicitField(
                key = key,
                title = s("title") ?: key,
                description = s("description").orEmpty(),
                kind = kind,
                required = key in required,
                options = options,
                default = def,
                format = s("format"),
                min = d("minimum") ?: d("minItems"),
                max = d("maximum") ?: d("maxItems"),
                minLength = d("minLength")?.toInt(),
                maxLength = d("maxLength")?.toInt(),
            )
        }
    }

    /** Checks one raw value from the form; returns an error to show, or null. */
    fun validate(f: ElicitField, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return if (f.required && f.kind != "boolean") "Required" else null
        return when (f.kind) {
            "integer" -> v.toLongOrNull()?.let { range(f, it.toDouble()) } ?: "Whole number"
            "number" -> v.toDoubleOrNull()?.let { range(f, it) } ?: "Number"
            "string" -> when {
                f.minLength != null && v.length < f.minLength -> "At least ${f.minLength} characters"
                f.maxLength != null && v.length > f.maxLength -> "At most ${f.maxLength} characters"
                f.format == "email" && !v.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) -> "Email address"
                (f.format == "uri" || f.format == "url") && !v.matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*:.+")) -> "URL"
                f.format == "date" && !v.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> "YYYY-MM-DD"
                else -> null
            }
            else -> null
        }
    }

    private fun range(f: ElicitField, x: Double): String? = when {
        f.min != null && x < f.min -> "≥ ${fmt(f.min)}"
        f.max != null && x > f.max -> "≤ ${fmt(f.max)}"
        else -> null
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    /** Turns the form's text values into typed JSON (empty optional fields are left out). */
    fun content(fields: List<ElicitField>, raw: Map<String, String>): Map<String, JsonElement> = buildMap {
        for (f in fields) {
            val v = raw[f.key]?.trim().orEmpty()
            when (f.kind) {
                "boolean" -> put(f.key, JsonPrimitive(v == "true"))
                "multi" -> if (v.isNotEmpty()) put(f.key, JsonArray(v.split(',').filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
                else -> if (v.isNotEmpty()) put(f.key, when (f.kind) {
                    "integer" -> JsonPrimitive(v.toLong())
                    "number" -> JsonPrimitive(v.toDouble())
                    else -> JsonPrimitive(v)
                })
            }
        }
    }

    fun boolDefault(f: ElicitField) = (f.default?.let { JsonPrimitive(it).booleanOrNull } ?: false).toString()
}
