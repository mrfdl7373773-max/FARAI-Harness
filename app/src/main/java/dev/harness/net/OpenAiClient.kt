package dev.harness.net

import dev.harness.data.settings.ProviderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Client for any OpenAI-compatible Chat Completions API, with SSE streaming,
 * cancellation, and retry with exponential backoff on transient failures.
 */
class OpenAiClient(
    private val config: suspend () -> ProviderSettings,
    private val baseHttp: OkHttpClient = OkHttpClient(),
    private val maxAttempts: Int = 7,
    private val initialBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 10_000,
) : ChatClient {

    @Volatile var lastRequestJson: String = ""
        private set
    @Volatile var lastResponseHeaders: Map<String, String> = emptyMap()
        private set
    /** Set when the provider rejected the chosen reasoning effort and another level (or none) was used. */
    @Volatile var lastReasoningNote: String? = null

    private fun http(p: ProviderSettings): OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(p.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun base(p: ProviderSettings) = p.baseUrl.trim().trimEnd('/')

    private fun Request.Builder.auth(p: ProviderSettings): Request.Builder = apply {
        if (p.apiKey.isNotBlank()) header("Authorization", "Bearer ${p.apiKey.trim()}")
        header("User-Agent", "FARAI-Harness/1.0 (Android)")
    }

    override suspend fun chat(request: ChatRequest, onDelta: (String) -> Unit): ChatResult = chat(request, onDelta) { }

    override suspend fun chat(request: ChatRequest, onDelta: (String) -> Unit, onRetry: (RetryInfo) -> Unit): ChatResult {
        val p = config().let { c ->
            if (request.effortOverride != null && c.reasoningEffort.isNotBlank()) c.copy(reasoningEffort = request.effortOverride) else c
        }
        var effort = ReasoningEffort.forRequest(p)
        var tokenCap: Int? = null
        var tokenCapSet = false
        fun bodyFor(r: ChatRequest): String {
            var b = ReasoningEffort.apply(HarnessJson.encodeToString(r), p, effort)
            if (tokenCapSet) b = MaxTokensFallback.withMaxTokens(b, tokenCap)
            return b
        }
        var current = request
        var body = bodyFor(current)
        lastRequestJson = body
        // Text already streamed before the connection dropped; the model is asked to continue after it.
        var prefix = ""
        var resumes = 0
        var attempt = 0
        var backoff = initialBackoffMs
        var effortRetried = false
        var tokensRetried = false
        while (true) {
            if (!Connectivity.isOnline) waitForNetwork(attempt, onRetry)
            attempt++
            val asm = StreamAssembler()
            try {
                val r = execute(p, body, request.stream, onDelta, asm)
                return if (prefix.isEmpty()) r else r.copy(content = prefix + r.content)
            } catch (e: ProviderException) {
                // The model rejected this effort level: step to the nearest one it lists, or drop it.
                if (effort != null && !effortRetried && e.statusCode in setOf(400, 422) && ReasoningEffort.isEffortError(e.body)) {
                    effortRetried = true
                    effort = ReasoningEffort.nearest(effort, ReasoningEffort.allowedFrom(e.body))
                    lastReasoningNote = if (effort == null) "The model rejected reasoning effort; sent without it."
                        else "The model does not accept `${ReasoningEffort.forRequest(p)}`; used `$effort` instead."
                    body = bodyFor(current)
                    lastRequestJson = body
                    attempt--
                    continue
                }
                // The model caps output below our max_tokens: retry once at its cap, or without the field.
                val asked = MaxTokensFallback.requested(body)
                if (asked != null && !tokensRetried && e.statusCode in setOf(400, 422) && MaxTokensFallback.isMaxTokensError(e.body)) {
                    tokensRetried = true
                    tokenCapSet = true
                    tokenCap = MaxTokensFallback.capFrom(e.body, asked)
                    body = bodyFor(current)
                    lastRequestJson = body
                    attempt--
                    continue
                }
                val transient = e.statusCode in setOf(408, 409, 425, 429) || e.statusCode in 500..599
                if (!transient || attempt >= maxAttempts) throw e
                val wait = (e.retryAfterMs ?: jitter(backoff)).coerceAtMost(MAX_RETRY_AFTER_MS)
                onRetry(RetryInfo(attempt, wait, if (e.statusCode == 429) "rate limited (429)" else "provider error ${e.statusCode}"))
                delay(wait)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
                continue
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                // Keep what was already written and ask the model to carry on after it, instead of starting over.
                val partial = asm.partialText()
                if (partial.isNotEmpty() && resumes < MAX_RESUMES) {
                    resumes++
                    prefix += partial
                    current = continuation(request, prefix)
                    body = bodyFor(current)
                    lastRequestJson = body
                    attempt = 0
                    backoff = initialBackoffMs
                }
                // Android reports a lost network a moment after the socket fails.
                if (Connectivity.isOnline) delay(OFFLINE_SETTLE_MS)
                if (!Connectivity.isOnline) {
                    attempt-- // time offline never uses up attempts
                    continue // waits at the top of the loop
                }
                if (attempt >= maxAttempts) throw e
                val wait = jitter(backoff)
                onRetry(RetryInfo(attempt, wait, "network: ${e.message ?: e::class.java.simpleName}"))
                delay(wait)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
                continue
            }
        }
    }

    private suspend fun waitForNetwork(attempt: Int, onRetry: (RetryInfo) -> Unit) {
        onRetry(RetryInfo(attempt, 0, "offline", RetryKind.OFFLINE))
        Connectivity.awaitOnline()
        onRetry(RetryInfo(attempt, 0, "online", RetryKind.ONLINE))
    }

    private fun jitter(ms: Long): Long = (ms * (0.8 + Math.random() * 0.4)).toLong()

    private suspend fun execute(p: ProviderSettings, body: String, stream: Boolean, onDelta: (String) -> Unit, asm: StreamAssembler): ChatResult {
        val req = Request.Builder()
            .url("${base(p)}/chat/completions")
            .auth(p)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val call = http(p).newCall(req)
        return coroutineScope {
            // A dropped connection can leave the socket hanging until the read timeout; cut it at once.
            val watcher = launch { Connectivity.awaitOffline(); call.cancel() }
            try {
                withCancellation(call) { call.execute().use { resp -> handle(resp, stream, onDelta, asm) } }
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun handle(resp: Response, stream: Boolean, onDelta: (String) -> Unit, asm: StreamAssembler): ChatResult {
        val headers = resp.headers.toMultimap().mapValues { it.value.joinToString(", ") }
        lastResponseHeaders = headers
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            throw ProviderException(resp.code, "HTTP ${resp.code}: ${errorMessage(text)}", text, retryAfter(resp))
        }
        val source = resp.body?.source() ?: throw IOException("Empty response body")
        val contentType = resp.header("Content-Type").orEmpty()
        if (!stream || !contentType.contains("event-stream")) {
            val text = source.readUtf8()
            val delta = asm.apply(text)
            if (delta.isNotEmpty()) onDelta(delta)
            return asm.result(headers, resp.code)
        }
        val parser = SseParser()
        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            val event = parser.feed(line) ?: continue
            if (event.trim() == SseParser.DONE) break
            val delta = asm.apply(event)
            if (delta.isNotEmpty()) onDelta(delta)
        }
        parser.flush()?.takeIf { it.trim() != SseParser.DONE }?.let { asm.apply(it).takeIf { d -> d.isNotEmpty() }?.let(onDelta) }
        return asm.result(headers, resp.code)
    }

    override suspend fun listModels(): List<String> {
        val p = config()
        val req = Request.Builder().url("${base(p)}/models").auth(p).get().build()
        val call = http(p).newCall(req)
        return withCancellation(call) {
            call.execute().use { resp ->
                lastResponseHeaders = resp.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ProviderException(resp.code, "HTTP ${resp.code}: ${errorMessage(text)}", text)
                val root = HarnessJson.parseToJsonElement(text).jsonObject
                val data = root["data"]?.jsonArray ?: root["models"]?.jsonArray
                data?.mapNotNull { el ->
                    runCatching { el.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        ?: runCatching { el.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }?.sorted() ?: emptyList()
            }
        }
    }

    /** Runs a blocking OkHttp call on IO, cancelling the socket if the coroutine is cancelled. */
    private suspend fun <T> withCancellation(call: Call, block: suspend () -> T): T {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            return withContext(Dispatchers.IO) { block() }
        } finally {
            handle?.dispose()
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val o = HarnessJson.parseToJsonElement(body).jsonObject
        o["error"]?.let { e ->
            runCatching { e.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: e.jsonPrimitive.contentOrNull
        } ?: o["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(300)

    /** Retry-After in seconds or as an HTTP date, also the retry-after-ms extension. */
    private fun retryAfter(resp: Response): Long? {
        resp.header("retry-after-ms")?.trim()?.toDoubleOrNull()?.let { return it.toLong() }
        val v = resp.header("Retry-After")?.trim() ?: return null
        v.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(v)!!.time - System.currentTimeMillis() }
            .getOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private const val MAX_RETRY_AFTER_MS = 60_000L
        private const val OFFLINE_SETTLE_MS = 1_500L
        /** How many times one reply may be resumed after a cut stream. */
        private const val MAX_RESUMES = 8
        const val CONTINUE_REMINDER = "<system-reminder>The connection dropped while you were writing the message above. " +
            "Continue exactly where it stops, mid-sentence if needed. Do not repeat or summarize what is already written, " +
            "and do not mention the interruption. If you were about to call a tool, call it now.</system-reminder>"

        /** [request] with the text written so far as an assistant message and a nudge to carry on. */
        fun continuation(request: ChatRequest, written: String): ChatRequest = request.copy(
            messages = request.messages + ChatMessage(Role.ASSISTANT, written) + ChatMessage(Role.USER, CONTINUE_REMINDER),
        )
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
