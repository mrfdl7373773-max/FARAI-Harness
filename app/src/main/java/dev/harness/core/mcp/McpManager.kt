package dev.harness.core.mcp

import dev.harness.tools.ShellBackend
import dev.harness.tools.Tool
import dev.harness.tools.ToolContext
import dev.harness.tools.ToolResult
import dev.harness.tools.schema
import dev.harness.tools.str
import dev.harness.tools.reqStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/** A tool a server offers, as listed by tools/list. */
data class McpRemoteTool(
    val server: String,
    val name: String,
    /** The name the model calls: `<server>_<tool>`, sanitized and at most 64 characters. */
    val exposedName: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
)

data class McpPrompt(val server: String, val name: String, val description: String, val arguments: List<Pair<String, Boolean>>)
data class McpResource(val server: String, val uri: String, val name: String, val mimeType: String?, val description: String)

enum class McpStatus { DISABLED, CONNECTING, CONNECTED, NEEDS_AUTH, FAILED }

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus,
    val error: String? = null,
    val tools: List<McpRemoteTool> = emptyList(),
    val prompts: List<McpPrompt> = emptyList(),
    val serverLabel: String? = null,
    val instructions: String? = null,
    val hasResources: Boolean = false,
)

/**
 * Keeps the configured MCP servers connected and turns their tools into FARAI tools.
 *
 * Servers are (re)connected lazily, right before an agent run, whenever the config changed or a
 * connection dropped. Local (stdio) servers run inside Alpine Linux when it is installed, so `npx`,
 * `uvx`, `python -m …` servers work as they do on a desktop.
 */
class McpManager(
    private val scope: CoroutineScope,
    private val configs: () -> List<McpServerConfig>,
    private val linux: () -> ShellBackend?,
    private val useLinux: () -> Boolean,
    private val workspaceRoot: File,
    private val http: OkHttpClient,
    /** OAuth token store and browser sign-in for remote servers; null turns OAuth off. */
    val auth: McpAuth? = null,
    /** Opens a page in the browser (URL elicitation). */
    private val openUrl: (String) -> Unit = {},
    /** Runs a sampling request on the user's model; null declines sampling. */
    private val sampler: (suspend (SamplingRequest) -> SamplingResponse)? = null,
) {
    private val _interactions = MutableStateFlow<List<McpInteraction>>(emptyList())
    /** What servers wait on the user for (forms, pages, sampling approvals), oldest first. */
    val interactions: StateFlow<List<McpInteraction>> = _interactions.asStateFlow()
    private val ids = java.util.concurrent.atomic.AtomicLong(0)
    /** Servers the user allowed to sample without asking again (until the app restarts). */
    private val samplingAllowed = java.util.Collections.synchronizedSet(HashSet<String>())
    /** The last 401 challenge per server, so /mcp auth can start from it. */
    private val challenges = java.util.concurrent.ConcurrentHashMap<String, String>()
    private class Live(val config: McpServerConfig, val connection: McpConnection)

    private val live = HashMap<String, Live>()
    private val lock = Mutex()
    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val states: StateFlow<Map<String, McpServerState>> = _states.asStateFlow()
    /** Servers that failed with this exact config: not retried automatically until it changes. */
    private val failedConfigs = HashMap<String, McpServerConfig>()

    private fun update(name: String, f: (McpServerState?) -> McpServerState?) {
        val m = _states.value.toMutableMap()
        val next = f(m[name])
        if (next == null) m.remove(name) else m[name] = next
        _states.value = m
    }

    /**
     * Brings connections in line with the config: closes removed or changed servers, connects new
     * ones (in parallel). Returns one line per server that just failed, for the chat.
     */
    suspend fun ensureConnected(force: Boolean = false): List<String> = lock.withLock {
        val wanted = runCatching { configs() }.getOrDefault(emptyList()).associateBy { it.name }
        // Drop servers no longer configured or whose config changed.
        for (name in live.keys.toList()) {
            val l = live[name]!!
            val w = wanted[name]
            if (w == null || !w.enabled || w != l.config || l.connection.closed) {
                l.connection.close(); live.remove(name)
            }
        }
        for (name in _states.value.keys.toList()) if (name !in wanted) update(name) { null }
        val toStart = wanted.values.filter { c ->
            if (!c.enabled) { update(c.name) { McpServerState(c, McpStatus.DISABLED) }; false }
            else c.name !in live && (force || failedConfigs[c.name] != c)
        }
        if (toStart.isEmpty()) return@withLock emptyList()
        val failures = coroutineScope {
            toStart.map { c -> async(Dispatchers.IO) { connect(c) } }.awaitAll().filterNotNull()
        }
        failures
    }

    /** Connects one server; returns an error line or null. */
    private suspend fun connect(c: McpServerConfig): String? {
        update(c.name) { McpServerState(c, McpStatus.CONNECTING) }
        val conn = when (c.transport) {
            McpTransport.STDIO -> StdioConnection(c, workspaceUri()) { processFor(c) }
            McpTransport.HTTP -> HttpConnection(c, workspaceUri(), http, auth)
            McpTransport.SSE -> SseConnection(c, workspaceUri(), http, auth)
        }
        conn.requestHandler = { server, method, params -> handleServerRequest(server, method, params) }
        return try {
            conn.start()
            conn.onListChanged = { what -> scope.launch { runCatching { refresh(c.name, what) } } }
            synchronized(live) { live[c.name] = Live(c, conn) }
            failedConfigs.remove(c.name)
            val tools = if (conn.has("tools")) listTools(c.name, conn) else emptyList()
            val prompts = if (conn.has("prompts")) runCatching { listPrompts(c.name, conn) }.getOrDefault(emptyList()) else emptyList()
            val info = conn.serverInfo
            val label = listOfNotNull((info?.get("title") ?: info?.get("name"))?.let { (it as? JsonPrimitive)?.contentOrNull },
                (info?.get("version") as? JsonPrimitive)?.contentOrNull).joinToString(" ").ifBlank { null }
            update(c.name) { McpServerState(c, McpStatus.CONNECTED, null, tools, prompts, label, conn.instructions, conn.has("resources")) }
            null
        } catch (e: McpAuthRequired) {
            conn.close()
            failedConfigs[c.name] = c
            e.wwwAuthenticate?.let { challenges[c.name] = it } ?: challenges.remove(c.name)
            val msg = if (auth?.hasLogin(c) == true) "the saved sign-in was rejected; sign in again with /mcp auth ${c.name}"
                else "needs you to sign in: run /mcp auth ${c.name}"
            update(c.name) { McpServerState(c, McpStatus.NEEDS_AUTH, msg) }
            "MCP server **${c.name}** $msg"
        } catch (e: Exception) {
            conn.close()
            failedConfigs[c.name] = c
            val msg = explain(c, e)
            update(c.name) { McpServerState(c, McpStatus.FAILED, msg) }
            "MCP server **${c.name}** failed to start: $msg"
        }
    }

    private fun explain(c: McpServerConfig, e: Exception): String {
        val base = (e.message ?: e::class.java.simpleName).take(900)
        return if (c.transport == McpTransport.STDIO && linux()?.available != true && useLinux())
            "$base\n(Local MCP servers need Alpine Linux with node/python: install it from Settings → Linux.)" else base
    }

    private suspend fun refresh(name: String, what: String) {
        val conn = synchronized(live) { live[name]?.connection } ?: return
        when (what) {
            "tools" -> { val t = listTools(name, conn); update(name) { it?.copy(tools = t) } }
            "prompts" -> { val p = listPrompts(name, conn); update(name) { it?.copy(prompts = p) } }
        }
    }

    suspend fun reconnect(name: String?): List<String> {
        lock.withLock {
            val names = if (name == null) live.keys.toList() else listOf(name)
            names.forEach { n -> live.remove(n)?.connection?.close(); failedConfigs.remove(n) }
            if (name != null) failedConfigs.remove(name) else failedConfigs.clear()
        }
        return ensureConnected(force = true)
    }

    fun disconnect(name: String) {
        synchronized(live) { live.remove(name)?.connection?.close() }
        _states.value[name]?.let { s -> update(name) { s.copy(status = McpStatus.FAILED, error = "disconnected (use /mcp connect ${s.config.name})", tools = emptyList()) } }
        _states.value[name]?.config?.let { failedConfigs[name] = it }
    }

    /** Servers waiting for a sign-in. */
    fun needingAuth(): List<String> = _states.value.values.filter { it.status == McpStatus.NEEDS_AUTH }.map { it.config.name }

    /**
     * Browser sign-in for a remote server, then reconnects it. Returns a line for the chat.
     * Suspends until the browser comes back or the sign-in times out.
     */
    suspend fun login(name: String): String {
        val a = auth ?: return "OAuth is not available."
        val c = runCatching { configs() }.getOrDefault(emptyList()).firstOrNull { it.name == name }
            ?: return "No MCP server named $name."
        if (c.transport == McpTransport.STDIO) return "$name is a local server; local servers take keys through \"environment\", not sign-in."
        if (!c.oauth.enabled) return "OAuth is turned off for $name (\"oauth\": false)."
        update(name) { (it ?: McpServerState(c, McpStatus.CONNECTING)).copy(status = McpStatus.CONNECTING, error = "waiting for the browser sign-in…") }
        return try {
            a.login(c, challenges[name])
            val failures = reconnect(name)
            val st = _states.value[name]
            if (st?.status == McpStatus.CONNECTED) "Signed in to **$name**: ${st.tools.size} tools ready."
            else "Signed in to **$name**, but it did not connect: " + (failures.firstOrNull() ?: st?.error ?: "unknown error")
        } catch (e: Exception) {
            update(name) { it?.copy(status = McpStatus.NEEDS_AUTH, error = "sign-in failed: ${e.message}") }
            "Sign-in to **$name** failed: ${e.message}"
        }
    }

    fun logout(name: String): Boolean {
        val removed = auth?.logout(name) == true
        disconnect(name)
        return removed
    }

    // ---- Requests from servers (elicitation, sampling) --------------------------------------

    private fun push(i: McpInteraction) { _interactions.value = _interactions.value + i }
    private fun pop(id: Long) { _interactions.value = _interactions.value.filterNot { it.id == id } }

    private suspend fun handleServerRequest(server: String, method: String, params: JsonObject?): JsonObject = when (method) {
        "elicitation/create" -> elicit(server, params)
        "sampling/createMessage" -> sample(server, params)
        else -> throw McpException("unsupported: $method", -32601)
    }

    private suspend fun elicit(server: String, params: JsonObject?): JsonObject {
        val message = (params?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val mode = (params?.get("mode") as? JsonPrimitive)?.contentOrNull ?: "form"
        val id = ids.incrementAndGet()
        val deferred = kotlinx.coroutines.CompletableDeferred<ElicitAnswer>()
        val interaction = if (mode == "url") {
            val url = (params?.get("url") as? JsonPrimitive)?.contentOrNull ?: throw McpException("url elicitation without a url", -32602)
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw McpException("only http(s) pages can be opened", -32602)
            McpInteraction.Url(id, server, message, url, deferred)
        } else {
            McpInteraction.Form(id, server, message, Elicitation.fields(params?.get("requestedSchema") as? JsonObject), deferred)
        }
        push(interaction)
        val answer = try { deferred.await() } finally { pop(id) }
        if (interaction is McpInteraction.Url && answer.action == "accept") openUrl(interaction.url)
        return buildJsonObject {
            put("action", answer.action)
            if (answer.action == "accept" && interaction is McpInteraction.Form) put("content", JsonObject(answer.content))
        }
    }

    private suspend fun sample(server: String, params: JsonObject?): JsonObject {
        val run = sampler ?: throw McpException("sampling is not available", -1)
        val cfg = _states.value[server]?.config
        if (cfg?.sampling == false) throw McpException("the user turned sampling off for $server", -1)
        val messages = (params?.get("messages") as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val role = (o["role"] as? JsonPrimitive)?.contentOrNull ?: "user"
            val text = contentText(o["content"])
            role to text
        }
        if (messages.isEmpty()) throw McpException("sampling request has no messages", -32602)
        val system = (params?.get("systemPrompt") as? JsonPrimitive)?.contentOrNull
        val maxTokens = (params?.get("maxTokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (cfg?.sampling != true && server !in samplingAllowed) {
            val id = ids.incrementAndGet()
            val deferred = kotlinx.coroutines.CompletableDeferred<SamplingDecision>()
            push(McpInteraction.Sampling(id, server, system, messages.joinToString("\n\n") { (r, t) -> "$r: $t" }.take(4000), maxTokens, deferred))
            val decision = try { deferred.await() } finally { pop(id) }
            when (decision) {
                SamplingDecision.DENY -> throw McpException("the user declined the sampling request", -1)
                SamplingDecision.ALWAYS -> samplingAllowed += server
                SamplingDecision.ONCE -> Unit
            }
        }
        val prefs = params?.get("modelPreferences") as? JsonObject
        val hints = (prefs?.get("hints") as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
        val result = run(SamplingRequest(
            server = server,
            systemPrompt = system,
            messages = messages,
            maxTokens = maxTokens,
            temperature = (params?.get("temperature") as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(),
            stopSequences = (params?.get("stopSequences") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            modelHints = hints,
        ))
        return buildJsonObject {
            put("role", "assistant")
            put("content", buildJsonObject { put("type", "text"); put("text", result.text) })
            put("model", result.model)
            put("stopReason", result.stopReason)
        }
    }

    private fun contentText(c: kotlinx.serialization.json.JsonElement?): String = when (c) {
        is JsonObject -> when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            "image" -> "[image omitted: this model connection sends text only]"
            "audio" -> "[audio omitted]"
            else -> c.toString()
        }
        is JsonArray -> c.joinToString("\n") { contentText(it) }
        else -> c?.toString().orEmpty()
    }

    /** Called by the UI. */
    fun answerElicitation(id: Long, answer: ElicitAnswer) {
        when (val i = _interactions.value.firstOrNull { it.id == id }) {
            is McpInteraction.Form -> i.answer.complete(answer)
            is McpInteraction.Url -> i.answer.complete(answer)
            else -> Unit
        }
    }

    fun answerSampling(id: Long, decision: SamplingDecision) {
        (_interactions.value.firstOrNull { it.id == id } as? McpInteraction.Sampling)?.answer?.complete(decision)
    }

    fun closeAll() { synchronized(live) { live.values.forEach { it.connection.close() }; live.clear() } }

    // ---- Catalog ---------------------------------------------------------------------------

    private suspend fun listTools(server: String, conn: McpConnection): List<McpRemoteTool> {
        val out = ArrayList<McpRemoteTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val r = conn.request("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } }, conn.config.startupTimeoutMs)
            (r["tools"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val ann = o["annotations"] as? JsonObject
                out += McpRemoteTool(
                    server = server,
                    name = name,
                    exposedName = exposedName(server, name),
                    title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: (ann?.get("title") as? JsonPrimitive)?.contentOrNull,
                    description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    inputSchema = cleanSchema(o["inputSchema"] as? JsonObject),
                    readOnly = (ann?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            cursor = (r["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null && ++pages < 20)
        return out
    }

    private suspend fun listPrompts(server: String, conn: McpConnection): List<McpPrompt> {
        val r = conn.request("prompts/list", null, conn.config.startupTimeoutMs)
        return (r["prompts"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val args = (o["arguments"] as? JsonArray)?.mapNotNull { a ->
                (a as? JsonObject)?.let { (it["name"] as? JsonPrimitive)?.contentOrNull?.to((it["required"] as? JsonPrimitive)?.booleanOrNull == true) }
            }.orEmpty()
            McpPrompt(server, name, (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), args)
        }.orEmpty()
    }

    /** Every tool of every connected server, as FARAI tools (plus mcp_resource when a server has resources). */
    fun tools(): List<Tool> {
        val st = _states.value.values.filter { it.status == McpStatus.CONNECTED }
        val list = ArrayList<Tool>()
        st.flatMap { it.tools }.forEach { list += McpToolAdapter(this, it) }
        if (st.any { it.hasResources }) list += McpResourceTool(this)
        return list
    }

    fun instructions(): List<Pair<String, String>> =
        _states.value.values.filter { it.status == McpStatus.CONNECTED && !it.instructions.isNullOrBlank() }.map { it.config.name to it.instructions!! }

    private fun conn(server: String): McpConnection =
        synchronized(live) { live[server]?.connection }?.takeIf { !it.closed } ?: throw McpException("MCP server \"$server\" is not connected (try /mcp connect $server)")

    suspend fun callTool(tool: McpRemoteTool, args: JsonObject, outputDir: File, display: (File) -> String): ToolResult {
        val c = conn(tool.server)
        val r = try {
            c.request("tools/call", buildJsonObject { put("name", tool.name); put("arguments", args) })
        } catch (e: McpException) {
            return ToolResult.error("${tool.exposedName}: ${e.message}")
        }
        return McpResults.toToolResult(r, outputDir, display)
    }

    suspend fun listResources(): List<McpResource> {
        val out = ArrayList<McpResource>()
        for (s in _states.value.values.filter { it.status == McpStatus.CONNECTED && it.hasResources }) {
            val c = runCatching { conn(s.config.name) }.getOrNull() ?: continue
            val r = runCatching { c.request("resources/list", null) }.getOrNull() ?: continue
            (r["resources"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                out += McpResource(s.config.name, (o["uri"] as? JsonPrimitive)?.contentOrNull ?: return@forEach,
                    (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["mimeType"] as? JsonPrimitive)?.contentOrNull,
                    (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
        }
        return out
    }

    suspend fun readResource(server: String, uri: String, outputDir: File, display: (File) -> String): ToolResult {
        val r = try { conn(server).request("resources/read", buildJsonObject { put("uri", uri) }) } catch (e: McpException) {
            return ToolResult.error(e.message ?: "read failed")
        }
        val parts = (r["contents"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val sb = StringBuilder()
        for (p in parts) {
            val text = (p["text"] as? JsonPrimitive)?.contentOrNull
            val blob = (p["blob"] as? JsonPrimitive)?.contentOrNull
            val mime = (p["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
            if (text != null) sb.append(text).append('\n')
            else if (blob != null) sb.append(McpResults.saveBlob(blob, mime, outputDir, display)).append('\n')
        }
        return ToolResult(McpResults.cap(sb.toString().trim().ifEmpty { "(empty resource)" }, outputDir, display))
    }

    /** Expands an MCP prompt into the text to send to the agent. */
    suspend fun getPrompt(server: String, name: String, args: Map<String, String>): String {
        val r = conn(server).request("prompts/get", buildJsonObject {
            put("name", name)
            put("arguments", JsonObject(args.mapValues { JsonPrimitive(it.value) }))
        })
        return (r["messages"] as? JsonArray).orEmpty().mapNotNull { m ->
            val content = (m as? JsonObject)?.get("content")
            when (content) {
                is JsonObject -> (content["text"] as? JsonPrimitive)?.contentOrNull
                    ?: ((content["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                is JsonArray -> content.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
                else -> null
            }
        }.joinToString("\n\n").trim()
    }

    // ---- Process ---------------------------------------------------------------------------

    private fun workspaceUri() = workspaceRoot.toURI().toString()

    private fun processFor(c: McpServerConfig): ProcessBuilder {
        val l = linux()?.takeIf { it.available && useLinux() }
        if (l != null) {
            val envPart = c.env.entries.joinToString(" ") { "${it.key}=${quote(it.value)}" }
            val cd = c.cwd?.let { "cd ${quote(it)} && " } ?: ""
            val cmd = "${cd}exec ${if (envPart.isNotEmpty()) "env $envPart " else ""}${c.command.joinToString(" ") { quote(it) }}"
            return l.processFor(cmd, workspaceRoot)
        }
        val cwd = c.cwd?.let { File(it).takeIf { f -> f.isAbsolute } ?: File(workspaceRoot, it) } ?: workspaceRoot
        return dev.harness.tools.BashTool.androidShell(c.command.joinToString(" ") { quote(it) }, cwd).also { pb ->
            pb.environment().putAll(c.env)
        }
    }

    companion object {
        fun quote(s: String): String = if (s.matches(Regex("[A-Za-z0-9_./:@%+=,-]+"))) s else "'" + s.replace("'", "'\\''") + "'"

        fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifEmpty { "x" }

        fun exposedName(server: String, tool: String): String {
            val full = "${sanitize(server)}_${sanitize(tool)}"
            if (full.length <= 64) return full
            val hash = MessageDigest.getInstance("SHA-1").digest(full.toByteArray()).take(3).joinToString("") { "%02x".format(it) }
            return full.take(57) + "_" + hash
        }

        /** Providers reject a few JSON-schema keywords and need a top-level object with properties. */
        fun cleanSchema(s: JsonObject?): JsonObject {
            val base = s ?: JsonObject(emptyMap())
            val m = base.toMutableMap()
            m.remove("\$schema")
            m.remove("\$id")
            m["type"] = JsonPrimitive("object")
            if (m["properties"] !is JsonObject) m["properties"] = JsonObject(emptyMap())
            return JsonObject(m)
        }
    }
}

/** One server tool, presented to the model as `<server>_<tool>`. */
class McpToolAdapter(private val manager: McpManager, val remote: McpRemoteTool) : Tool {
    override val name = remote.exposedName
    /** Optional local override of the server's description: prompts/tools/mcp/<name>.md */
    override val promptPath = "tools/mcp/${remote.exposedName}.md"
    override val parameters: JsonObject = remote.inputSchema
    override val readOnly = remote.readOnly
    override val fallbackDescription: String get() =
        "[MCP server \"${remote.server}\", tool \"${remote.name}\"]" + (remote.title?.let { " $it." } ?: "") + "\n" +
            remote.description.ifBlank { "(The server gave no description; infer from the parameters.)" }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        manager.callTool(remote, args, File(ctx.workspace.root, ".farai/tool-output")) { ctx.workspace.relative(it) }
}

/** Lists and reads resources that MCP servers expose (files, docs, records). */
class McpResourceTool(private val manager: McpManager) : Tool {
    override val name = "mcp_resource"
    override val promptPath = "tools/mcp_resource.md"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("action", required = true, enum = listOf("list", "read"))
        string("server")
        string("uri")
    }
    override val fallbackDescription = "List (action=list) or read (action=read, server, uri) resources exposed by connected MCP servers."

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val out = File(ctx.workspace.root, ".farai/tool-output")
        return when (args.reqStr("action")) {
            "list" -> {
                val list = manager.listResources()
                if (list.isEmpty()) ToolResult("no resources") else ToolResult(list.take(200).joinToString("\n") { r ->
                    "${r.server}  ${r.uri}  ${r.name}${r.mimeType?.let { " ($it)" } ?: ""}${if (r.description.isNotBlank()) " — ${r.description.take(120)}" else ""}"
                })
            }
            "read" -> {
                val server = args.str("server") ?: return ToolResult.error("'server' is required")
                val uri = args.str("uri") ?: return ToolResult.error("'uri' is required")
                manager.readResource(server, uri, out) { ctx.workspace.relative(it) }
            }
            else -> ToolResult.error("action must be list or read")
        }
    }
}

/** Turns an MCP tools/call result into one text the model can read. */
object McpResults {
    private const val CAP = 60_000

    fun toToolResult(r: JsonObject, outputDir: File, display: (File) -> String): ToolResult {
        val isError = (r["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val parts = ArrayList<String>()
        (r["content"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (o["text"] as? JsonPrimitive)?.contentOrNull?.let { parts += it }
                "image", "audio" -> {
                    val data = (o["data"] as? JsonPrimitive)?.contentOrNull
                    val mime = (o["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
                    if (data != null) parts += saveBlob(data, mime, outputDir, display)
                }
                "resource" -> (o["resource"] as? JsonObject)?.let { res ->
                    val uri = (res["uri"] as? JsonPrimitive)?.contentOrNull ?: "resource"
                    val text = (res["text"] as? JsonPrimitive)?.contentOrNull
                    val blob = (res["blob"] as? JsonPrimitive)?.contentOrNull
                    val mime = (res["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
                    parts += when {
                        text != null -> "[Resource $uri]\n$text"
                        blob != null -> saveBlob(blob, mime, outputDir, display)
                        else -> "[Resource $uri]"
                    }
                }
                "resource_link" -> parts += "[Resource link: ${(o["uri"] as? JsonPrimitive)?.contentOrNull} ${(o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()}]"
                else -> parts += o.toString()
            }
        }
        if (parts.isEmpty()) r["structuredContent"]?.let { parts += it.toString() }
        val text = parts.joinToString("\n").trim().ifEmpty { if (isError) "the MCP tool returned an error" else "(no output)" }
        val capped = cap(text, outputDir, display)
        return if (isError) ToolResult.error(capped) else ToolResult(capped)
    }

    fun saveBlob(base64: String, mime: String, outputDir: File, display: (File) -> String): String {
        val bytes = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull() ?: return "[$mime content could not be decoded]"
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")) return String(bytes, Charsets.UTF_8)
        outputDir.mkdirs()
        val ext = when {
            mime.contains("png") -> "png"; mime.contains("jpeg") || mime.contains("jpg") -> "jpg"; mime.contains("gif") -> "gif"
            mime.contains("webp") -> "webp"; mime.contains("pdf") -> "pdf"; mime.contains("wav") -> "wav"; mime.contains("mpeg") -> "mp3"
            else -> "bin"
        }
        val f = File(outputDir, "mcp-${System.currentTimeMillis()}.$ext")
        f.writeBytes(bytes)
        return "[$mime, ${bytes.size} bytes, saved to ${display(f)}]"
    }

    fun cap(text: String, outputDir: File, display: (File) -> String): String {
        if (text.length <= CAP) return text
        outputDir.mkdirs()
        val f = File(outputDir, "mcp-${System.currentTimeMillis()}.txt")
        f.writeText(text)
        return text.take(CAP * 2 / 3) + "\n… [${text.length - CAP} characters omitted] …\n" + text.takeLast(CAP / 3) +
            "\n[full output saved to ${display(f)}; read or grep it for the rest]"
    }
}
