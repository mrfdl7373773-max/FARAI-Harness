package dev.harness

import dev.harness.core.mcp.McpAuth
import dev.harness.core.mcp.McpManager
import dev.harness.core.mcp.McpOAuthConfig
import dev.harness.core.mcp.McpServerConfig
import dev.harness.core.mcp.McpStatus
import dev.harness.core.mcp.McpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * End-to-end OAuth against a small Python server that plays both the MCP resource server and the
 * authorization server: 401 challenge → resource metadata → AS metadata → dynamic registration →
 * authorize (the "browser" follows the redirect to our loopback) → token → authorized MCP calls →
 * refresh after expiry.
 */
class McpOAuthTest {

    private val server = """
import json, sys, base64, hashlib, urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
port = int(sys.argv[1])
base = "http://127.0.0.1:%d" % port
state = {"codes": {}, "tokens": set(), "refresh": set(), "n": 0, "clients": {}}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def j(self, code, obj, headers=None):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u = urllib.parse.urlparse(self.path); q = dict(urllib.parse.parse_qsl(u.query))
        if u.path == "/.well-known/oauth-protected-resource/mcp":
            return self.j(200, {"resource": base + "/mcp", "authorization_servers": [base + "/auth"], "scopes_supported": ["tools"]})
        if u.path == "/.well-known/oauth-authorization-server/auth":
            return self.j(200, {"issuer": base + "/auth", "authorization_endpoint": base + "/auth/authorize", "token_endpoint": base + "/auth/token",
                                "registration_endpoint": base + "/auth/register", "code_challenge_methods_supported": ["S256"]})
        if u.path == "/auth/authorize":
            assert q["client_id"] in state["clients"], "unknown client"
            assert q["code_challenge_method"] == "S256" and q["resource"] == base + "/mcp" and q["scope"] == "tools"
            code = "code%d" % len(state["codes"])
            state["codes"][code] = (q["code_challenge"], q["redirect_uri"])
            self.send_response(302)
            self.send_header("Location", q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": code, "state": q["state"]}))
            self.end_headers(); return
        self.j(404, {})
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n).decode()
        u = urllib.parse.urlparse(self.path)
        if u.path == "/auth/register":
            meta = json.loads(body); cid = "client%d" % len(state["clients"]); state["clients"][cid] = meta
            return self.j(201, {"client_id": cid, "redirect_uris": meta["redirect_uris"]})
        if u.path == "/auth/token":
            f = dict(urllib.parse.parse_qsl(body))
            if f["grant_type"] == "authorization_code":
                ch, redir = state["codes"].pop(f["code"])
                calc = base64.urlsafe_b64encode(hashlib.sha256(f["code_verifier"].encode()).digest()).rstrip(b"=").decode()
                if calc != ch or redir != f["redirect_uri"] or f["resource"] != base + "/mcp": return self.j(400, {"error": "invalid_grant"})
            elif f["grant_type"] == "refresh_token":
                if f["refresh_token"] not in state["refresh"]: return self.j(400, {"error": "invalid_grant"})
                state["refresh"].discard(f["refresh_token"])
            state["n"] += 1
            tok, ref = "tok%d" % state["n"], "ref%d" % state["n"]
            state["tokens"] = {tok}; state["refresh"].add(ref)
            # The first token expires at once so the client must refresh.
            return self.j(200, {"access_token": tok, "refresh_token": ref, "token_type": "Bearer", "expires_in": 0 if state["n"] == 1 else 3600})
        if u.path == "/mcp":
            auth = self.headers.get("Authorization", "")
            if auth.replace("Bearer ", "") not in state["tokens"]:
                return self.j(401, {"error": "unauthorized"}, {"WWW-Authenticate": 'Bearer resource_metadata="%s/.well-known/oauth-protected-resource/mcp", scope="tools"' % base})
            m = json.loads(body); i = m.get("id")
            if i is None: self.send_response(202); self.send_header("Content-Length", "0"); self.end_headers(); return
            meth = m["method"]
            if meth == "initialize": return self.j(200, {"jsonrpc": "2.0", "id": i, "result": {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "secure", "version": "1"}}}, {"Mcp-Session-Id": "s1"})
            if meth == "tools/list": return self.j(200, {"jsonrpc": "2.0", "id": i, "result": {"tools": [{"name": "whoami", "inputSchema": {"type": "object"}}]}})
            if meth == "tools/call": return self.j(200, {"jsonrpc": "2.0", "id": i, "result": {"content": [{"type": "text", "text": "you hold " + auth}]}})
            return self.j(200, {"jsonrpc": "2.0", "id": i, "error": {"code": -32601, "message": "no"}})
        self.j(404, {})
HTTPServer(("127.0.0.1", port), H).serve_forever()
""".trimIndent()

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun fullBrowserSignInThenRefresh() = runBlocking {
        val py = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue(py != null)
        val dir = TestSupport.tempDir("oauth")
        val port = freePort()
        val proc = ProcessBuilder(py!!, File(dir, "as.py").apply { writeText(server) }.path, "$port").redirectErrorStream(true).start()
        try {
            val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
            // Wait for the server.
            repeat(50) { if (runCatching { http.newCall(Request.Builder().url("http://127.0.0.1:$port/x").build()).execute().close() }.isSuccess) return@repeat; Thread.sleep(100) }

            val callback = freePort()
            // The "browser": follow the authorize redirect to our loopback listener.
            val browser = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(true).readTimeout(10, TimeUnit.SECONDS).build()
            val opened = mutableListOf<String>()
            val auth = McpAuth(File(dir, "auth"), http) { url ->
                opened += url
                Thread { runCatching { browser.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } } }.start()
            }
            val cfg = McpServerConfig("secure", McpTransport.HTTP, url = "http://127.0.0.1:$port/mcp",
                oauth = McpOAuthConfig(redirectUri = "http://127.0.0.1:$callback/mcp/oauth/callback"), startupTimeoutMs = 10_000, callTimeoutMs = 10_000)
            val m = McpManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), { listOf(cfg) }, { null }, { false }, dir, http, auth = auth)

            // Not signed in: the connect ends in NEEDS_AUTH with a pointer to /mcp auth.
            val first = m.ensureConnected()
            assertTrue(first.single(), first.single().contains("/mcp auth secure"))
            assertEquals(McpStatus.NEEDS_AUTH, m.states.value["secure"]!!.status)
            assertEquals(listOf("secure"), m.needingAuth())

            // Sign in: discovery, registration, PKCE, loopback, token, reconnect.
            val line = m.login("secure")
            assertTrue(line, line.startsWith("Signed in to **secure**: 1 tools"))
            assertTrue(opened.single().contains("code_challenge_method=S256"))
            assertTrue(auth.hasLogin(cfg))
            // tok1 came back already expired, so reconnecting refreshed it to tok2.
            assertEquals("tok2", auth.load("secure")!!.accessToken)

            // Calls carry the refreshed token.
            val st = m.states.value["secure"]!!
            assertEquals(McpStatus.CONNECTED, st.status)
            val r = m.callTool(st.tools.single(), kotlinx.serialization.json.JsonObject(emptyMap()), File(dir, "out")) { it.name }
            assertEquals("you hold Bearer tok2", r.output)

            // Sign out forgets the tokens.
            assertTrue(m.logout("secure"))
            assertFalse(auth.hasLogin(cfg))
        } finally {
            proc.destroy()
        }
    }

    @Test fun parsesChallenges() {
        val p = McpAuth.parseChallenge("""Bearer realm="mcp", resource_metadata="https://a.dev/.well-known/oauth-protected-resource", scope="read write", error=invalid_token""")
        assertEquals("https://a.dev/.well-known/oauth-protected-resource", p["resource_metadata"])
        assertEquals("read write", p["scope"])
        assertEquals("invalid_token", p["error"])
        assertEquals("https://a.dev/mcp", McpAuth.canonicalResource("https://A.dev/mcp/#x"))
        assertEquals(McpManager.sanitize("a.b"), "a_b")
    }
}
