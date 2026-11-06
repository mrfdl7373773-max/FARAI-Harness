package dev.harness.core.mcp

import dev.harness.net.HarnessJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What an HTTP transport needs from the token store. */
interface McpHttpAuth {
    /** A valid access token for this server, refreshed first if it expired; null when not signed in. */
    suspend fun token(config: McpServerConfig): String?
    /** Tries the refresh token; true when a new access token is stored. */
    suspend fun refresh(config: McpServerConfig): Boolean
}

/** A sign-in, as stored in the app's private files (filesDir/mcp-auth/<server>.json). */
@Serializable
data class McpCredential(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch ms; 0 = no expiry given. */
    val expiresAt: Long = 0,
    val tokenType: String = "Bearer",
    val scope: String? = null,
    val issuer: String? = null,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String,
    val resource: String? = null,
)

/** Discovered endpoints of an authorization server. */
data class AuthServer(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopesSupported: List<String> = emptyList(),
    val codeChallengeMethods: List<String> = emptyList(),
)

/**
 * OAuth 2.1 for remote MCP servers, following the MCP authorization spec:
 * protected-resource metadata (RFC 9728) → authorization-server metadata (RFC 8414 / OpenID) →
 * dynamic client registration (RFC 7591) unless a client id is configured → authorization code with
 * PKCE S256 and the resource indicator (RFC 8707) → token exchange and refresh. The browser returns
 * to a loopback listener on 127.0.0.1 (RFC 8252), so no app link is needed.
 */
class McpAuth(
    private val dir: File,
    private val http: OkHttpClient,
    private val openUrl: (String) -> Unit,
) : McpHttpAuth {
    private val locks = HashMap<String, Mutex>()
    private val random = SecureRandom()

    private fun lock(name: String) = synchronized(locks) { locks.getOrPut(name) { Mutex() } }
    private fun file(name: String) = File(dir, McpManager.sanitize(name) + ".json")

    fun load(name: String): McpCredential? =
        runCatching { HarnessJson.decodeFromString(McpCredential.serializer(), file(name).readText()) }.getOrNull()

    private fun save(name: String, c: McpCredential) {
        dir.mkdirs()
        val f = file(name)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(HarnessJson.encodeToString(McpCredential.serializer(), c))
        tmp.renameTo(f)
    }

    fun logout(name: String): Boolean = file(name).delete()

    /** Signed in to this server at this URL (a changed URL needs a new sign-in). */
    fun hasLogin(config: McpServerConfig) = load(config.name)?.serverUrl == config.url

    override suspend fun token(config: McpServerConfig): String? {
        val c = load(config.name)?.takeIf { it.serverUrl == config.url } ?: return null
        if (c.expiresAt == 0L || c.expiresAt - 60_000 > System.currentTimeMillis()) return c.accessToken
        return if (refresh(config)) load(config.name)?.accessToken else null
    }

    override suspend fun refresh(config: McpServerConfig): Boolean = lock(config.name).withLock {
        val c = load(config.name)?.takeIf { it.serverUrl == config.url } ?: return@withLock false
        // Another caller may have refreshed while we waited for the lock.
        if (c.expiresAt != 0L && c.expiresAt - 60_000 > System.currentTimeMillis()) return@withLock true
        val refresh = c.refreshToken ?: return@withLock false
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", c.clientId)
        c.clientSecret?.let { form.add("client_secret", it) }
        c.resource?.let { form.add("resource", it) }
        val tokens = runCatching { tokenRequest(c.tokenEndpoint, form.build()) }.getOrNull()
        if (tokens == null) {
            // invalid_grant and friends: the stored login is dead, the user must sign in again.
            logout(config.name)
            return@withLock false
        }
        save(config.name, c.copy(
            accessToken = tokens.access,
            refreshToken = tokens.refresh ?: c.refreshToken,
            expiresAt = tokens.expiresAt,
            scope = tokens.scope ?: c.scope,
        ))
        true
    }

    // ---- Sign-in -----------------------------------------------------------------------

    /**
     * Runs the whole browser sign-in for [config] and stores the tokens. [challenge] is the
     * WWW-Authenticate header from the server's 401, when there was one. Suspends until the browser
     * comes back (or [timeoutMs]).
     */
    suspend fun login(config: McpServerConfig, challenge: String?, timeoutMs: Long = 300_000): McpCredential = withContext(Dispatchers.IO) {
        val url = config.url ?: throw IOException("${config.name} has no url")
        val oauth = config.oauth
        val challenged = challenge ?: probeChallenge(config)
        val params = parseChallenge(challenged)

        // 1. Where to sign in: the protected resource names its authorization server.
        val prm = fetchResourceMetadata(url, params["resource_metadata"])
        val resource = (prm?.get("resource") as? JsonPrimitive)?.contentOrNull ?: canonicalResource(url)
        val issuer = (prm?.get("authorization_servers") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        val asMeta = discoverAuthServer(issuer ?: origin(url))
            ?: if (issuer == null) legacyEndpoints(url) else throw IOException("could not read the authorization server metadata at $issuer")
        val methods = asMeta.codeChallengeMethods
        if (methods.isNotEmpty() && "S256" !in methods) throw IOException("${asMeta.issuer} does not support PKCE S256, which MCP requires")
        val scope = oauth.scope ?: params["scope"]
            ?: ((prm?.get("scopes_supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ")?.ifBlank { null })

        // 2. Who we are: a configured client, the one registered last time, or a new registration.
        val redirect = oauth.redirectUri
        val previous = load(config.name)
        val (clientId, clientSecret) = when {
            oauth.clientId != null -> oauth.clientId to oauth.clientSecret
            previous != null && previous.issuer == asMeta.issuer && previous.redirectUri == redirect -> previous.clientId to previous.clientSecret
            asMeta.registrationEndpoint != null -> register(asMeta.registrationEndpoint, redirect, scope)
            else -> throw IOException("${asMeta.issuer} does not allow automatic client registration. Register an OAuth app there and put its id in \"oauth\": { \"clientId\": \"…\" } for ${config.name}.")
        }

        // 3. The browser round trip, with PKCE and state.
        val verifier = randomToken(48)
        val challengeValue = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomToken(24)
        val authUrl = asMeta.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("code_challenge", challengeValue)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .apply { scope?.let { addQueryParameter("scope", it) } }
            .addQueryParameter("resource", resource)
            .build().toString()
        val code = awaitRedirect(redirect, state, authUrl, config.name, timeoutMs)

        // 4. Code for tokens.
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .add("resource", resource)
        clientSecret?.let { form.add("client_secret", it) }
        val tokens = tokenRequest(asMeta.tokenEndpoint, form.build())
        val cred = McpCredential(
            serverUrl = url,
            accessToken = tokens.access,
            refreshToken = tokens.refresh,
            expiresAt = tokens.expiresAt,
            tokenType = tokens.type,
            scope = tokens.scope ?: scope,
            issuer = asMeta.issuer,
            tokenEndpoint = asMeta.tokenEndpoint,
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirect,
            resource = resource,
        )
        save(config.name, cred)
        cred
    }

    /** POSTs an empty initialize to learn the server's WWW-Authenticate challenge. */
    private fun probeChallenge(config: McpServerConfig): String? = runCatching {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 0); put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", McpConnection.PROTOCOL_VERSION)
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject { put("name", "farai-harness"); put("version", "2.0.0") })
            })
        }
        val b = Request.Builder().url(config.url!!).post(body.toString().toRequestBody(McpConnection.JSON))
            .header("Accept", "application/json, text/event-stream")
        config.headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { it.header("WWW-Authenticate") }
    }.getOrNull()

    private fun getJson(url: String): JsonObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").header("MCP-Protocol-Version", McpConnection.PROTOCOL_VERSION).build())
            .execute().use { r -> if (!r.isSuccessful) null else HarnessJson.parseToJsonElement(r.body!!.string()).jsonObject }
    }.getOrNull()

    /** RFC 9728: the header's resource_metadata URL, else the well-known paths (path-aware first). */
    fun fetchResourceMetadata(serverUrl: String, hinted: String?): JsonObject? {
        val u = serverUrl.toHttpUrl()
        val path = u.encodedPath.trimEnd('/')
        val candidates = listOfNotNull(
            hinted,
            if (path.isNotEmpty()) "${origin(serverUrl)}/.well-known/oauth-protected-resource$path" else null,
            "${origin(serverUrl)}/.well-known/oauth-protected-resource",
        )
        return candidates.firstNotNullOfOrNull { getJson(it)?.takeIf { j -> j["authorization_servers"] != null || j["resource"] != null } }
    }

    /** RFC 8414 and OpenID discovery, in the order the MCP spec lists them. */
    fun discoverAuthServer(issuer: String): AuthServer? {
        val u = issuer.toHttpUrlOrNull() ?: return null
        val o = "${u.scheme}://${u.host}${if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""}"
        val path = u.encodedPath.trimEnd('/')
        val candidates = if (path.isNotEmpty()) listOf(
            "$o/.well-known/oauth-authorization-server$path",
            "$o/.well-known/openid-configuration$path",
            "$o$path/.well-known/openid-configuration",
        ) else listOf("$o/.well-known/oauth-authorization-server", "$o/.well-known/openid-configuration")
        val meta = candidates.firstNotNullOfOrNull { getJson(it)?.takeIf { j -> j["authorization_endpoint"] != null && j["token_endpoint"] != null } } ?: return null
        fun s(k: String) = (meta[k] as? JsonPrimitive)?.contentOrNull
        fun l(k: String) = (meta[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return AuthServer(
            issuer = s("issuer") ?: issuer,
            authorizationEndpoint = s("authorization_endpoint")!!,
            tokenEndpoint = s("token_endpoint")!!,
            registrationEndpoint = s("registration_endpoint"),
            scopesSupported = l("scopes_supported"),
            codeChallengeMethods = l("code_challenge_methods_supported"),
        )
    }

    /** 2025-03-26 servers without metadata: the default endpoints on the server's origin. */
    private fun legacyEndpoints(serverUrl: String): AuthServer {
        val o = origin(serverUrl)
        return AuthServer(o, "$o/authorize", "$o/token", "$o/register")
    }

    private fun register(endpoint: String, redirect: String, scope: String?): Pair<String, String?> {
        val body = buildJsonObject {
            put("client_name", "FARAI Harness")
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(redirect)) })
            put("grant_types", buildJsonArray { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) })
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
            scope?.let { put("scope", it) }
        }
        val r = http.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody(McpConnection.JSON)).header("Accept", "application/json").build()).execute()
        r.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("client registration failed: HTTP ${it.code} ${text.take(300)}")
            val o = HarnessJson.parseToJsonElement(text).jsonObject
            val id = (o["client_id"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("client registration returned no client_id")
            return id to (o["client_secret"] as? JsonPrimitive)?.contentOrNull
        }
    }

    data class Tokens(val access: String, val refresh: String?, val expiresAt: Long, val type: String, val scope: String?)

    private fun tokenRequest(endpoint: String, form: FormBody): Tokens {
        val r = http.newCall(Request.Builder().url(endpoint).post(form).header("Accept", "application/json").build()).execute()
        r.use {
            val text = it.body?.string().orEmpty()
            // Some servers (GitHub) answer form-encoded unless asked otherwise; accept both.
            val fields: Map<String, String> = runCatching {
                HarnessJson.parseToJsonElement(text).jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
            }.getOrElse { parseQuery(text) }
            if (!it.isSuccessful || fields["access_token"] == null) {
                val err = fields["error_description"] ?: fields["error"] ?: text.take(300)
                throw IOException("token request failed (HTTP ${it.code}): $err")
            }
            val expires = fields["expires_in"]?.toLongOrNull()?.let { s -> System.currentTimeMillis() + s * 1000 } ?: 0L
            return Tokens(fields["access_token"]!!, fields["refresh_token"], expires, fields["token_type"] ?: "Bearer", fields["scope"])
        }
    }

    /**
     * Opens [authUrl] in the browser and waits on the loopback [redirect] for the authorization
     * server to send the browser back with ?code=…&state=….
     */
    private fun awaitRedirect(redirect: String, state: String, authUrl: String, server: String, timeoutMs: Long): String {
        val r = redirect.toHttpUrl()
        if (r.host != "127.0.0.1" && r.host != "localhost") throw IOException("redirect must be a loopback address (http://127.0.0.1:PORT/…), got $redirect")
        val socket = try { ServerSocket(r.port, 8, InetAddress.getByName("127.0.0.1")) } catch (e: IOException) {
            throw IOException("could not listen on 127.0.0.1:${r.port} for the sign-in callback (${e.message}). Is another sign-in still open?")
        }
        socket.use { ss ->
            val deadline = System.currentTimeMillis() + timeoutMs
            openUrl(authUrl)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IOException("sign-in timed out after ${timeoutMs / 60_000} minutes")
                ss.soTimeout = left.toInt().coerceAtLeast(1)
                val client = try { ss.accept() } catch (e: SocketTimeoutException) { continue }
                client.use { c ->
                    c.soTimeout = 10_000
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                    val target = line.split(' ').getOrNull(1).orEmpty()
                    val path = target.substringBefore('?')
                    val q = parseQuery(target.substringAfter('?', ""))
                    val out = c.getOutputStream()
                    fun reply(status: String, html: String) {
                        val bytes = html.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes); out.flush()
                    }
                    if (path != r.encodedPath) { reply("404 Not Found", "Not found"); return@use }
                    val error = q["error_description"] ?: q["error"]
                    when {
                        error != null -> { reply("400 Bad Request", page(server, false, error)); throw IOException("sign-in refused: $error") }
                        // Prefetches and reloads can hit the callback without parameters: keep waiting.
                        q["code"] == null && q["state"] == null -> reply("400 Bad Request", page(server, false, "Missing authorization code"))
                        q["state"] != state -> { reply("400 Bad Request", page(server, false, "State mismatch")); throw IOException("sign-in failed: state mismatch") }
                        else -> { reply("200 OK", page(server, true, null)); return q["code"]!! }
                    }
                }
            }
        }
    }

    private fun page(server: String, ok: Boolean, error: String?): String {
        val back = "intent://#Intent;package=dev.farai.harness;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;end"
        val title = if (ok) "Signed in to ${esc(server)}" else "Sign-in failed"
        val body = if (ok) "You can go back to FARAI. The server connects on its own." else esc(error ?: "")
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>FARAI</title>
<style>body{font-family:system-ui,sans-serif;background:#0f0f14;color:#eee;display:flex;min-height:90vh;align-items:center;justify-content:center;margin:0}
main{max-width:420px;padding:28px;text-align:center}h1{font-size:22px}p{color:#aaa;line-height:1.5}
a{display:inline-block;margin-top:18px;padding:12px 22px;border-radius:999px;background:#6d5efc;color:#fff;text-decoration:none;font-weight:600}</style></head>
<body><main><h1>$title</h1><p>$body</p><a href="$back">Back to FARAI</a></main></body></html>"""
    }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun origin(url: String): String {
            val u = url.toHttpUrl()
            val port = if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
            return "${u.scheme}://${u.host}$port"
        }

        /** RFC 8707 canonical resource: scheme and host lower-case, no fragment, no trailing slash. */
        fun canonicalResource(url: String): String {
            val u = url.toHttpUrl().newBuilder().fragment(null).build()
            return u.toString().trimEnd('/')
        }

        /** `Bearer realm="x", resource_metadata="…", scope="a b"` → parameters (keys lower-cased). */
        fun parseChallenge(header: String?): Map<String, String> {
            if (header.isNullOrBlank()) return emptyMap()
            val out = HashMap<String, String>()
            Regex("([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,\\s]+))").findAll(header).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }.replace("\\\"", "\"")
            }
            return out
        }

        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    private fun randomToken(bytes: Int): String = b64(ByteArray(bytes).also { random.nextBytes(it) })
}
