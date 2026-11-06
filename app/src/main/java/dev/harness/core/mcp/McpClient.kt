package dev.harness.core.mcp

import dev.harness.net.HarnessJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpException(message: String, val code: Int = 0) : IOException(message)

/** The server answered 401: it wants an OAuth access token (or a better one). */
class McpAuthRequired(val server: String, val wwwAuthenticate: String?) :
    IOException("$server needs you to sign in (run /mcp auth $server)")

/** Handles a request the server sends us (elicitation, sampling). Throw [McpException] to answer with an error. */
typealias McpRequestHandler = suspend (server: String, method: String, params: JsonObject?) -> JsonObject

/**
 * A JSON-RPC 2.0 connection to one MCP server. Subclasses move bytes (stdio, Streamable HTTP,
 * legacy SSE); this class does the protocol: the initialize handshake, request/response matching,
 * server-to-client requests (ping, roots/list) and list-changed notifications.
 */
abstract class McpConnection(val config: McpServerConfig, private val workspaceUri: String) {
    private val ids = AtomicLong(0)
    protected val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    @Volatile var closed = false
        protected set
    @Volatile var closeReason: String? = null
        protected set

    var serverInfo: JsonObject? = null; private set
    var capabilities: JsonObject = JsonObject(emptyMap()); private set
    var instructions: String? = null; private set
    var protocolVersion: String? = null; private set
    /** Set by the manager: called when the server says its tool/prompt/resource list changed. */
    @Volatile var onListChanged: (String) -> Unit = {}
    /** Answers elicitation/create and sampling/createMessage; null declares neither capability. */
    @Volatile var requestHandler: McpRequestHandler? = null
    /** Server requests can wait minutes on the user, so they never run on a transport's reader thread. */
    protected val handlerScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    protected abstract suspend fun open()
    /** Sends one message. For HTTP the response may come back on the same call through [dispatch]. */
    protected abstract suspend fun transmit(message: JsonObject)
    abstract fun close()

    suspend fun start() {
        withTimeout(config.startupTimeoutMs) {
            open()
            val result = request("initialize", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("capabilities") {
                    putJsonObject("roots") { put("listChanged", false) }
                    if (requestHandler != null) {
                        // Form and URL elicitation (2025-06-18 servers read only that the key exists).
                        putJsonObject("elicitation") { putJsonObject("form") {}; putJsonObject("url") {} }
                        putJsonObject("sampling") {}
                    }
                }
                putJsonObject("clientInfo") { put("name", "farai-harness"); put("version", "2.0.0") }
            }, config.startupTimeoutMs)
            protocolVersion = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            serverInfo = result["serverInfo"] as? JsonObject
            capabilities = (result["capabilities"] as? JsonObject) ?: JsonObject(emptyMap())
            instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            notify("notifications/initialized", null)
        }
    }

    fun has(capability: String) = capabilities.containsKey(capability)

    suspend fun request(method: String, params: JsonObject?, timeoutMs: Long = config.callTimeoutMs): JsonObject {
        if (closed) throw McpException("connection to ${config.name} is closed${closeReason?.let { ": $it" } ?: ""}")
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method)
            if (params != null) put("params", params)
        }
        try {
            transmit(msg)
            val response = withTimeout(timeoutMs) { deferred.await() }
            (response["error"] as? JsonObject)?.let { e ->
                val code = (e["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                throw McpException((e["message"] as? JsonPrimitive)?.contentOrNull ?: e.toString(), code)
            }
            return (response["result"] as? JsonObject) ?: JsonObject(emptyMap())
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw McpException("${config.name}: no answer to $method after ${timeoutMs / 1000}s")
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JsonObject?) {
        transmit(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); if (params != null) put("params", params) })
    }

    /** Routes a message from the server. */
    protected suspend fun dispatch(msg: JsonObject) {
        val id = msg["id"]
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
        if (method == null) {
            val key = (id as? JsonPrimitive)?.longOrNull ?: (id as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
            pending[key]?.complete(msg)
            return
        }
        if (id == null || id is JsonNull) {
            when (method) {
                "notifications/tools/list_changed" -> onListChanged("tools")
                "notifications/prompts/list_changed" -> onListChanged("prompts")
                "notifications/resources/list_changed" -> onListChanged("resources")
            }
            return
        }
        // A request from the server to us.
        val handler = requestHandler
        if (handler != null && (method == "elicitation/create" || method == "sampling/createMessage")) {
            val params = msg["params"] as? JsonObject
            handlerScope.launch {
                val reply = try {
                    val result = handler(config.name, method, params)
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id)
                        putJsonObject("error") { put("code", (e as? McpException)?.code?.takeIf { it != 0 } ?: -32603); put("message", e.message ?: "failed") }
                    }
                }
                runCatching { transmit(reply) }
            }
            return
        }
        val reply = when (method) {
            "ping" -> buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("result") {} }
            "roots/list" -> buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                putJsonObject("result") {
                    put("roots", buildJsonArray { add(buildJsonObject { put("uri", workspaceUri); put("name", "workspace") }) })
                }
            }
            else -> buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                putJsonObject("error") { put("code", -32601); put("message", "FARAI does not support $method") }
            }
        }
        runCatching { transmit(reply) }
    }

    protected fun failAll(reason: String) {
        handlerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        closed = true
        closeReason = reason
        pending.values.forEach { it.completeExceptionally(McpException("${config.name}: $reason")) }
        pending.clear()
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        val JSON = "application/json".toMediaType()
    }
}

/** A local server: a process whose stdin/stdout carry newline-delimited JSON-RPC. */
class StdioConnection(
    config: McpServerConfig,
    workspaceUri: String,
    private val processBuilder: () -> ProcessBuilder,
) : McpConnection(config, workspaceUri) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val stderr = StringBuffer()

    override suspend fun open(): Unit = withContext(Dispatchers.IO) {
        val pb = processBuilder()
        pb.redirectErrorStream(false)
        val p = try { pb.start() } catch (e: Exception) { throw McpException("could not start `${config.summary}`: ${e.message}") }
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        Thread({
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    stderr.append(line).append('\n')
                    if (stderr.length > 6000) stderr.delete(0, stderr.length - 4000)
                }
            }
        }, "mcp-${config.name}-err").apply { isDaemon = true; start() }
        Thread({
            val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    // Servers sometimes log to stdout; anything that is not a JSON object is ignored.
                    if (!t.startsWith("{")) continue
                    val obj = runCatching { HarnessJson.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
                    kotlinx.coroutines.runBlocking { dispatch(obj) }
                }
            }
            val code = runCatching { p.waitFor(2, TimeUnit.SECONDS); p.exitValue() }.getOrNull()
            failAll("the server exited${code?.let { " (code $it)" } ?: ""}" + stderrTail().let { if (it.isBlank()) "" else ":\n$it" })
        }, "mcp-${config.name}-out").apply { isDaemon = true; start() }
    }

    fun stderrTail(): String = stderr.toString().trim().takeLast(1200)

    override suspend fun transmit(message: JsonObject): Unit = withContext(Dispatchers.IO) {
        val w = writer ?: throw McpException("${config.name} is not running")
        synchronized(w) {
            try { w.write(message.toString()); w.write("\n"); w.flush() } catch (e: IOException) {
                throw McpException("${config.name}: write failed (${e.message})" + stderrTail().let { if (it.isBlank()) "" else "\n$it" })
            }
        }
    }

    override fun close() {
        if (!closed) failAll("closed")
        runCatching { writer?.close() }
        process?.let { p ->
            p.destroy()
            Thread { if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }.apply { isDaemon = true; start() }
        }
    }
}

/** Streamable HTTP: each message is a POST; the answer is JSON or a short SSE stream. */
class HttpConnection(
    config: McpServerConfig,
    workspaceUri: String,
    private val http: OkHttpClient,
    private val auth: McpHttpAuth? = null,
) : McpConnection(config, workspaceUri) {
    @Volatile private var sessionId: String? = null
    private val client: OkHttpClient by lazy {
        http.newBuilder().readTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS).callTimeout(config.callTimeoutMs + 5000, TimeUnit.MILLISECONDS).build()
    }

    override suspend fun open() {}

    override suspend fun transmit(message: JsonObject): Unit = withContext(Dispatchers.IO) {
        send(message, retried = false)
    }

    private suspend fun send(message: JsonObject, retried: Boolean) {
        val b = Request.Builder().url(config.url!!).post(message.toString().toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "FARAI-Harness/2.0 (Android)")
        config.headers.forEach { (k, v) -> b.header(k, v) }
        if (config.headers.keys.none { it.equals("Authorization", true) }) auth?.token(config)?.let { b.header("Authorization", "Bearer $it") }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        protocolVersion?.let { b.header("MCP-Protocol-Version", it) }
        val resp = try { client.newCall(b.build()).execute() } catch (e: IOException) {
            throw McpException("${config.name}: ${e.message ?: e::class.java.simpleName}")
        }
        resp.use { r ->
            r.header("Mcp-Session-Id")?.let { sessionId = it }
            if (r.code == 202 || r.code == 204) return
            if (r.code == 401 && auth != null && config.oauth.enabled && config.headers.keys.none { it.equals("Authorization", true) }) {
                val challenge = r.header("WWW-Authenticate")
                // An expired token is refreshed once; anything else needs the user to sign in.
                if (!retried && auth.refresh(config)) { r.close(); return send(message, retried = true) }
                throw McpAuthRequired(config.name, challenge)
            }
            if (!r.isSuccessful) {
                val body = r.body?.string().orEmpty().take(400)
                val hint = when (r.code) { 401, 403 -> " (the server needs authorization: add a token under \"headers\", e.g. \"Authorization\": \"Bearer …\")"; else -> "" }
                throw McpException("${config.name}: HTTP ${r.code}$hint ${body}".trim(), r.code)
            }
            val type = r.header("Content-Type").orEmpty()
            if (type.contains("event-stream")) readSse(r) else {
                val text = r.body?.string().orEmpty().trim()
                if (text.isEmpty()) return
                when (val el = runCatching { HarnessJson.parseToJsonElement(text) }.getOrNull()) {
                    is JsonObject -> dispatch(el)
                    is kotlinx.serialization.json.JsonArray -> el.forEach { (it as? JsonObject)?.let { o -> dispatch(o) } }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun readSse(r: Response) {
        val source = r.body?.source() ?: return
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    runCatching { HarnessJson.parseToJsonElement(data.toString()).jsonObject }.getOrNull()?.let { dispatch(it) }
                    data.clear()
                    // One request, one answer: stop reading once nothing we asked for is still open.
                    if (pending.isEmpty()) break
                }
                continue
            }
            if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
        }
    }

    override fun close() {
        if (closed) return
        failAll("closed")
        val sid = sessionId ?: return
        // Tell the server we are done with this session (best effort).
        Thread {
            runCatching {
                val b = Request.Builder().url(config.url!!).delete().header("Mcp-Session-Id", sid)
                config.headers.forEach { (k, v) -> b.header(k, v) }
                client.newCall(b.build()).execute().close()
            }
        }.apply { isDaemon = true; start() }
    }
}

/** The 2024-11-05 HTTP+SSE transport: a long GET stream for answers, POSTs to the endpoint it names. */
class SseConnection(
    config: McpServerConfig,
    workspaceUri: String,
    private val http: OkHttpClient,
    private val auth: McpHttpAuth? = null,
) : McpConnection(config, workspaceUri) {
    private suspend fun bearer(b: Request.Builder) {
        if (config.headers.keys.none { it.equals("Authorization", true) }) auth?.token(config)?.let { b.header("Authorization", "Bearer $it") }
    }
    @Volatile private var endpoint: String? = null
    private var call: okhttp3.Call? = null
    private val ready = CompletableDeferred<Unit>()
    private val client: OkHttpClient by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    override suspend fun open() {
        val b = Request.Builder().url(config.url!!).get().header("Accept", "text/event-stream")
        config.headers.forEach { (k, v) -> b.header(k, v) }
        bearer(b)
        val c = client.newCall(b.build())
        call = c
        Thread({
            try {
                c.execute().use { r ->
                    if (r.code == 401 && auth != null && config.oauth.enabled) { ready.completeExceptionally(McpAuthRequired(config.name, r.header("WWW-Authenticate"))); return@use }
                    if (!r.isSuccessful) { ready.completeExceptionally(McpException("${config.name}: HTTP ${r.code}", r.code)); return@use }
                    val source = r.body!!.source()
                    var event = "message"
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                val payload = data.toString(); data.clear()
                                if (event == "endpoint") {
                                    endpoint = r.request.url.resolve(payload.trim())?.toString() ?: payload.trim()
                                    ready.complete(Unit)
                                } else if (payload.isNotBlank()) {
                                    runCatching { HarnessJson.parseToJsonElement(payload).jsonObject }.getOrNull()?.let { kotlinx.coroutines.runBlocking { dispatch(it) } }
                                }
                                event = "message"
                            }
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
                        }
                    }
                }
                failAll("the event stream ended")
            } catch (e: Exception) {
                ready.completeExceptionally(if (e is McpAuthRequired) e else McpException("${config.name}: ${e.message}"))
                failAll(e.message ?: "stream failed")
            }
        }, "mcp-${config.name}-sse").apply { isDaemon = true; start() }
        ready.await()
    }

    override suspend fun transmit(message: JsonObject): Unit = withContext(Dispatchers.IO) {
        val url = endpoint ?: throw McpException("${config.name}: no endpoint yet")
        val b = Request.Builder().url(url).post(message.toString().toRequestBody(JSON))
        config.headers.forEach { (k, v) -> b.header(k, v) }
        bearer(b)
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 401 && auth != null && config.oauth.enabled) throw McpAuthRequired(config.name, r.header("WWW-Authenticate"))
            if (!r.isSuccessful && r.code != 202) throw McpException("${config.name}: HTTP ${r.code} ${r.body?.string().orEmpty().take(300)}", r.code)
        }
    }

    override fun close() {
        if (!closed) failAll("closed")
        call?.cancel()
    }
}
