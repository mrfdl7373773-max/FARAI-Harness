package dev.harness.core.mcp

import dev.harness.net.HarnessJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * OAuth for a remote server (the `oauth` key). On by default: a server that answers 401 can
 * be signed in to with /mcp auth. `"oauth": false` turns it off (e.g. when a token goes in headers).
 */
data class McpOAuthConfig(
    val enabled: Boolean = true,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scope: String? = null,
    /** Loopback redirect the browser returns to; registered with the authorization server. */
    val redirectUri: String = DEFAULT_REDIRECT,
) {
    companion object {
        const val DEFAULT_PORT = 19876
        const val DEFAULT_REDIRECT = "http://127.0.0.1:$DEFAULT_PORT/mcp/oauth/callback"
    }
}

/** How FARAI reaches a server. */
enum class McpTransport { STDIO, HTTP, SSE }

/**
 * One MCP server, as written in a config file. Three formats are read, so configs can be copied
 * from other tools unchanged:
 *
 *  - FARAI: `{"mcp": {"name": {"type": "local", "command": ["npx", "-y", "pkg"], "environment": {...}}}}`
 *    and `{"type": "remote", "url": "https://…", "headers": {...}}`
 *  - the common `mcpServers` layout: `{"mcpServers": {"name": {"command": "npx", "args": [...], "env": {...}}}}`
 *    and `{"url": "…", "type": "http" | "sse", "headers": {...}}`
 *  - a bare map of servers.
 */
data class McpServerConfig(
    val name: String,
    val transport: McpTransport,
    /** stdio: program and arguments. */
    val command: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** stdio: working directory (guest path when Linux runs it), default the workspace. */
    val cwd: String? = null,
    /** http / sse */
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** Milliseconds to wait for startup / one tool call. */
    val startupTimeoutMs: Long = DEFAULT_STARTUP_MS,
    val callTimeoutMs: Long = DEFAULT_CALL_MS,
    val oauth: McpOAuthConfig = McpOAuthConfig(),
    /** Sampling: null asks each time, true allows without asking, false always declines. */
    val sampling: Boolean? = null,
    /** Where it was defined, for /mcp. */
    val source: String = "",
) {
    val summary: String get() = when (transport) {
        McpTransport.STDIO -> command.joinToString(" ")
        else -> "${transport.name.lowercase()} $url"
    }

    companion object {
        const val DEFAULT_STARTUP_MS = 60_000L
        const val DEFAULT_CALL_MS = 300_000L
    }
}

object McpConfig {

    /** Every config FARAI reads, lowest priority first; a later file's server replaces an earlier one with the same name. */
    fun load(globalMarkdown: String?, workspace: File?): List<McpServerConfig> {
        val byName = LinkedHashMap<String, McpServerConfig>()
        globalMarkdown?.let { md -> parseJsonBlocks(md).forEach { parse(it, "prompts/mcp.md").forEach { s -> byName[s.name] = s } } }
        if (workspace != null) {
            for (rel in WORKSPACE_FILES) {
                val f = File(workspace, rel)
                if (!f.isFile) continue
                runCatching { HarnessJson.parseToJsonElement(stripJsonComments(f.readText())).jsonObject }.getOrNull()
                    ?.let { parse(it, rel).forEach { s -> byName[s.name] = s } }
            }
        }
        return byName.values.toList()
    }

    val WORKSPACE_FILES = listOf(".mcp.json", "farai.json", ".farai/mcp.json")

    /** The JSON objects inside ```json fences of a Markdown file (or the whole file if it is JSON). */
    fun parseJsonBlocks(md: String): List<JsonObject> {
        val blocks = Regex("(?s)```(?:jsonc?|JSON)?\\s*\\n(.*?)```").findAll(md).map { it.groupValues[1] }.toList()
            .ifEmpty { listOf(md) }
        return blocks.mapNotNull { b -> runCatching { HarnessJson.parseToJsonElement(stripJsonComments(b)).jsonObject }.getOrNull() }
    }

    /** Removes line and block comments outside strings, and trailing commas (jsonc). */
    fun stripJsonComments(text: String): String {
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++; continue
            }
            when {
                c == '"' -> { inStr = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { while (i < text.length && text[i] != '\n') i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> { i += 2; while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++; i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }

    fun parse(root: JsonObject, source: String): List<McpServerConfig> {
        val map = (root["mcp"] as? JsonObject) ?: (root["mcpServers"] as? JsonObject) ?: (root["servers"] as? JsonObject)
            ?: root.takeIf { r -> r.values.all { it is JsonObject } && r.isNotEmpty() && r.keys.none { it == "\$schema" } }
            ?: return emptyList()
        return map.mapNotNull { (name, v) -> (v as? JsonObject)?.let { runCatching { server(name, it, source) }.getOrNull() } }
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    private fun strMap(o: JsonObject, k: String): Map<String, String> =
        (o[k] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()

    private fun server(name: String, o: JsonObject, source: String): McpServerConfig? {
        val type = str(o, "type")?.lowercase()
        val url = str(o, "url") ?: str(o, "serverUrl") ?: str(o, "httpUrl")
        val command: List<String> = when (val c = o["command"]) {
            is JsonArray -> c.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(c.contentOrNull) + ((o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
            else -> emptyList()
        }
        val transport = when {
            type == "sse" -> McpTransport.SSE
            type in setOf("remote", "http", "streamable-http", "streamablehttp") || (command.isEmpty() && url != null) ->
                if (url?.trimEnd('/')?.endsWith("/sse") == true && type != "http" && type != "streamable-http") McpTransport.SSE else McpTransport.HTTP
            command.isNotEmpty() -> McpTransport.STDIO
            else -> return null
        }
        // "timeout" is the startup timeout in ms; also accept an object { startup, execution }.
        val timeout = o["timeout"]
        val startup = (timeout as? JsonPrimitive)?.longOrNull ?: (timeout as? JsonObject)?.let { (it["startup"] as? JsonPrimitive)?.longOrNull }
        val exec = (timeout as? JsonObject)?.let { (it["execution"] as? JsonPrimitive)?.longOrNull }
        val oauth = when (val o2 = o["oauth"]) {
            is JsonPrimitive -> McpOAuthConfig(enabled = o2.booleanOrNull != false)
            is JsonObject -> {
                val port = (o2["callbackPort"] as? JsonPrimitive)?.intOrNull ?: (o2["callback_port"] as? JsonPrimitive)?.intOrNull
                McpOAuthConfig(
                    enabled = true,
                    clientId = str(o2, "clientId") ?: str(o2, "client_id"),
                    clientSecret = str(o2, "clientSecret") ?: str(o2, "client_secret"),
                    scope = str(o2, "scope"),
                    redirectUri = str(o2, "redirectUri") ?: str(o2, "redirect_uri")
                        ?: port?.let { "http://127.0.0.1:$it/mcp/oauth/callback" } ?: McpOAuthConfig.DEFAULT_REDIRECT,
                )
            }
            else -> McpOAuthConfig()
        }
        return McpServerConfig(
            name = name,
            transport = transport,
            command = command,
            env = strMap(o, "environment") + strMap(o, "env"),
            cwd = str(o, "cwd"),
            url = url,
            headers = strMap(o, "headers"),
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: ((o["disabled"] as? JsonPrimitive)?.booleanOrNull?.not()) ?: true,
            // First start may download the package (npx/uvx), so never wait less than the default.
            startupTimeoutMs = maxOf(startup ?: 0L, McpServerConfig.DEFAULT_STARTUP_MS),
            callTimeoutMs = exec?.coerceAtLeast(5_000) ?: ((o["callTimeout"] as? JsonPrimitive)?.intOrNull?.toLong()) ?: McpServerConfig.DEFAULT_CALL_MS,
            oauth = oauth,
            sampling = (o["sampling"] as? JsonPrimitive)?.booleanOrNull,
            source = source,
        )
    }
}
