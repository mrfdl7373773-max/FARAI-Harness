package dev.harness.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val HarnessJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

object Role {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolSchema(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema>? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = true,
    /** Overrides the session's reasoning effort for this one call (e.g. "low" for titles). Not sent as-is. */
    @kotlinx.serialization.Transient val effortOverride: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/** The assembled result of one model call (streamed or not). */
data class ChatResult(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val usage: Usage? = null,
    val headers: Map<String, String> = emptyMap(),
    val statusCode: Int = 200,
)

class ProviderException(val statusCode: Int, message: String, val body: String = "", val retryAfterMs: Long? = null) : Exception(message)

/** A transient failure the client is about to retry after [delayMs]. */
data class RetryInfo(val attempt: Int, val delayMs: Long, val reason: String, val kind: RetryKind = RetryKind.BACKOFF)

/**
 * BACKOFF: waiting [RetryInfo.delayMs] before trying again. OFFLINE: the internet is gone and the call
 * waits for it. ONLINE: it is back and the call carries on (after a cut stream, from where it stopped).
 */
enum class RetryKind { BACKOFF, OFFLINE, ONLINE }

/** Abstraction over the model API so the engine can be tested with a fake. */
interface ChatClient {
    suspend fun chat(request: ChatRequest, onDelta: (String) -> Unit = {}): ChatResult
    /** Like [chat], and reports each retry so the UI can say why it is waiting. */
    suspend fun chat(request: ChatRequest, onDelta: (String) -> Unit, onRetry: (RetryInfo) -> Unit): ChatResult = chat(request, onDelta)
    suspend fun listModels(): List<String>
}
