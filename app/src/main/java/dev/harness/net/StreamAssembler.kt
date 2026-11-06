package dev.harness.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Accumulates OpenAI-style streaming chunks into a [ChatResult]. */
class StreamAssembler {
    private val content = StringBuilder()
    private val calls = sortedMapOf<Int, PartialCall>()
    private var finishReason: String? = null
    private var usage: Usage? = null

    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    /** Applies one chunk; returns the text delta it carried (possibly empty). */
    fun apply(chunkJson: String): String {
        val obj = runCatching { HarnessJson.parseToJsonElement(chunkJson).jsonObject }.getOrNull() ?: return ""
        obj["usage"]?.let { u -> if (u is JsonObject) usage = runCatching { HarnessJson.decodeFromJsonElement<Usage>(u) }.getOrNull() }
        obj["error"]?.let { e ->
            val msg = (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
            throw ProviderException(0, msg, chunkJson)
        }
        val choices = obj["choices"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return ""
        val choice = choices.firstOrNull()?.jsonObject ?: return ""
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
        val delta = (choice["delta"] ?: choice["message"])?.let { it as? JsonObject } ?: return ""
        var text = ""
        delta["content"]?.let { c -> runCatching { c.jsonPrimitive.contentOrNull }.getOrNull()?.let { text = it } }
        if (text.isNotEmpty()) content.append(text)
        delta["tool_calls"]?.let { arr ->
            runCatching { arr.jsonArray }.getOrNull()?.forEachIndexed { pos, el ->
                val tc = el.jsonObject
                val index = tc["index"]?.jsonPrimitive?.intOrNull ?: pos
                val partial = calls.getOrPut(index) { PartialCall() }
                tc["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) partial.id = it }
                tc["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.jsonPrimitive?.contentOrNull?.let { partial.name += it }
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { partial.args.append(it) }
                }
            }
        }
        return text
    }

    /** Text received so far (used to resume after a dropped stream). */
    fun partialText(): String = content.toString()

    fun result(headers: Map<String, String>, status: Int): ChatResult = ChatResult(
        content = content.toString(),
        toolCalls = calls.entries.filter { it.value.name.isNotEmpty() }.map { (i, p) ->
            ToolCall(
                id = p.id.ifEmpty { "call_$i" },
                function = FunctionCall(p.name, p.args.toString().ifBlank { "{}" }),
            )
        },
        finishReason = finishReason,
        usage = usage,
        headers = headers,
        statusCode = status,
    )
}
