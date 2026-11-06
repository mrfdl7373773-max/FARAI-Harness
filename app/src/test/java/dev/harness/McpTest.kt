package dev.harness

import dev.harness.core.mcp.McpConfig
import dev.harness.core.mcp.McpManager
import dev.harness.core.mcp.McpServerConfig
import dev.harness.core.mcp.McpStatus
import dev.harness.core.mcp.McpTransport
import dev.harness.core.commands.builtins.McpCommand
import dev.harness.tools.ShellBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class McpTest {

    @Test fun parsesBothLayoutsAndMarkdown() {
        val md = """
            # MCP
            ```json
            { "mcp": {
                // a comment
                "fs": { "type": "local", "command": ["npx", "-y", "srv", "/workspace"], "environment": {"A": "1"} },
                "docs": { "type": "remote", "url": "https://x.dev/mcp", "headers": {"Authorization": "Bearer t"} },
                "old": { "type": "remote", "url": "https://y.dev/sse", "enabled": false },
            } }
            ```
            ```text
            "ignored": { "command": ["nope"] }
            ```
        """.trimIndent()
        val ws = TestSupport.tempDir("mcpws")
        File(ws, ".mcp.json").writeText("""{"mcpServers": {"gh": {"command": "uvx", "args": ["mcp-gh"], "env": {"T": "x"}}, "docs": {"url": "https://z.dev/mcp", "type": "http"}}}""")
        val list = McpConfig.load(md, ws).associateBy { it.name }
        assertEquals(setOf("fs", "docs", "old", "gh"), list.keys)
        assertEquals(listOf("npx", "-y", "srv", "/workspace"), list["fs"]!!.command)
        assertEquals("1", list["fs"]!!.env["A"])
        assertEquals("https://z.dev/mcp", list["docs"]!!.url) // workspace file wins
        assertEquals(McpTransport.SSE, list["old"]!!.transport)
        assertFalse(list["old"]!!.enabled)
        assertEquals(listOf("uvx", "mcp-gh"), list["gh"]!!.command)
        assertEquals("gh_create_issue", McpManager.exposedName("gh", "create-issue").replace('-', '_'))
        assertTrue(McpManager.exposedName("a".repeat(40), "b".repeat(40)).length <= 64)
    }

    @Test fun addRewritesTheJsonBlock() {
        val out = McpCommand.rewrite(McpCommand.DEFAULT_MD) { it + ("x" to JsonObject(mapOf("type" to JsonPrimitive("remote"), "url" to JsonPrimitive("https://a")))) }
        val parsed = McpConfig.load(out, null)
        assertEquals("https://a", parsed.single().url)
    }

    private val server = """
import sys, json
def send(o):
    sys.stdout.write(json.dumps(o) + "\n"); sys.stdout.flush()
print("server log line that is not json", flush=True)
for line in sys.stdin:
    m = json.loads(line)
    meth = m.get("method"); i = m.get("id")
    if meth == "initialize":
        send({"jsonrpc":"2.0","id":i,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{},"prompts":{}},"serverInfo":{"name":"echo","version":"1.0"},"instructions":"Use echo_say to repeat text."}})
    elif meth == "tools/list":
        send({"jsonrpc":"2.0","id":i,"result":{"tools":[{"name":"say","description":"Repeat text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},"annotations":{"readOnlyHint":True}},{"name":"boom","description":"Fails","inputSchema":{"type":"object"}}]}})
    elif meth == "prompts/list":
        send({"jsonrpc":"2.0","id":i,"result":{"prompts":[{"name":"hello","arguments":[{"name":"who","required":True}]}]}})
    elif meth == "prompts/get":
        send({"jsonrpc":"2.0","id":i,"result":{"messages":[{"role":"user","content":{"type":"text","text":"Say hello to " + m["params"]["arguments"]["who"]}}]}})
    elif meth == "tools/call":
        p = m["params"]
        if p["name"] == "ask":
            send({"jsonrpc":"2.0","id":"e1","method":"elicitation/create","params":{"message":"Who?","requestedSchema":{"type":"object","properties":{"name":{"type":"string","title":"Name"},"age":{"type":"integer","minimum":1},"color":{"type":"string","enum":["r","g"],"enumNames":["Red","Green"]}},"required":["name"]}}})
            ans = json.loads(sys.stdin.readline())
            r = ans["result"]
            send({"jsonrpc":"2.0","id":i,"result":{"content":[{"type":"text","text":r["action"] + ":" + json.dumps(r.get("content"), sort_keys=True)}]}})
            continue
        if p["name"] == "think":
            send({"jsonrpc":"2.0","id":"s1","method":"sampling/createMessage","params":{"messages":[{"role":"user","content":{"type":"text","text":"2+2?"}}],"systemPrompt":"math","maxTokens":50}})
            ans = json.loads(sys.stdin.readline())
            out = ans["result"]["content"]["text"] if "result" in ans else "ERR " + ans["error"]["message"]
            send({"jsonrpc":"2.0","id":i,"result":{"content":[{"type":"text","text":out}]}})
            continue
        if p["name"] == "boom":
            send({"jsonrpc":"2.0","id":i,"result":{"isError":True,"content":[{"type":"text","text":"it broke"}]}})
        else:
            send({"jsonrpc":"2.0","id":i,"result":{"content":[{"type":"text","text":"echo: " + p["arguments"]["text"]}]}})
    elif i is not None:
        send({"jsonrpc":"2.0","id":i,"error":{"code":-32601,"message":"nope"}})
""".trimIndent()

    private fun python(): String? = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }

    @Test fun stdioServerEndToEnd() = runBlocking {
        val py = python()
        assumeTrue(py != null)
        val dir = TestSupport.tempDir("mcpsrv")
        val script = File(dir, "srv.py").apply { writeText(server) }
        val cfg = McpServerConfig("echo", McpTransport.STDIO, command = listOf(py!!, script.path), startupTimeoutMs = 15_000)
        // A "Linux" backend that just runs the command line with sh, like PRoot would.
        val backend = object : ShellBackend {
            override val label = "test"
            override val available = true
            override fun processFor(command: String, hostCwd: File) = ProcessBuilder("/bin/sh", "-c", command).directory(hostCwd)
        }
        val m = McpManager(CoroutineScope(SupervisorJob() + Dispatchers.IO), { listOf(cfg) }, { backend }, { true }, dir, OkHttpClient())
        val failures = m.ensureConnected()
        assertTrue(failures.toString(), failures.isEmpty())
        val st = m.states.value["echo"]!!
        assertEquals(McpStatus.CONNECTED, st.status)
        assertEquals(listOf("echo_say", "echo_boom"), m.tools().map { it.name })
        assertTrue(m.tools().first().readOnly)
        assertEquals(listOf("echo" to "Use echo_say to repeat text."), m.instructions())
        val out = File(dir, "out")
        val ok = m.callTool(st.tools[0], JsonObject(mapOf("text" to JsonPrimitive("hi"))), out) { it.name }
        assertEquals("echo: hi", ok.output)
        val bad = m.callTool(st.tools[1], JsonObject(emptyMap()), out) { it.name }
        assertTrue(bad.isError && bad.output.contains("it broke"))
        assertEquals("Say hello to Farrel", m.getPrompt("echo", "hello", mapOf("who" to "Farrel")))
        m.closeAll()
    }

    @Test fun elicitationAndSamplingRoundTrip() = runBlocking {
        val py = python()
        assumeTrue(py != null)
        val dir = TestSupport.tempDir("mcpel")
        val script = File(dir, "srv.py").apply { writeText(server) }
        val cfg = McpServerConfig("echo", McpTransport.STDIO, command = listOf(py!!, script.path), startupTimeoutMs = 15_000)
        val backend = object : ShellBackend {
            override val label = "test"
            override val available = true
            override fun processFor(command: String, hostCwd: File) = ProcessBuilder("/bin/sh", "-c", command).directory(hostCwd)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = McpManager(scope, { listOf(cfg) }, { backend }, { true }, dir, OkHttpClient(),
            sampler = { req -> dev.harness.core.mcp.SamplingResponse("4 (${req.systemPrompt}, ${req.maxTokens})", "test-model", "endTurn") })
        assertTrue(m.ensureConnected().isEmpty())
        val st = m.states.value["echo"]!!
        val ask = dev.harness.core.mcp.McpRemoteTool("echo", "ask", "echo_ask", null, "", JsonObject(emptyMap()), false)
        val think = dev.harness.core.mcp.McpRemoteTool("echo", "think", "echo_think", null, "", JsonObject(emptyMap()), false)
        val out = File(dir, "out")

        // The UI side: answer the form as soon as it shows up.
        val call = scope.async { m.callTool(ask, JsonObject(emptyMap()), out) { it.name } }
        val form = kotlinx.coroutines.withTimeout(10_000) { m.interactions.first { it.isNotEmpty() } }.first() as dev.harness.core.mcp.McpInteraction.Form
        assertEquals("Who?", form.message)
        assertEquals(listOf("name", "age", "color"), form.fields.map { it.key })
        assertEquals("enum", form.fields[2].kind)
        assertEquals("Red", form.fields[2].options[0].second)
        assertEquals("Required", dev.harness.core.mcp.Elicitation.validate(form.fields[0], ""))
        assertEquals("≥ 1", dev.harness.core.mcp.Elicitation.validate(form.fields[1], "0"))
        val content = dev.harness.core.mcp.Elicitation.content(form.fields, mapOf("name" to "Farrel", "age" to "19", "color" to "g"))
        m.answerElicitation(form.id, dev.harness.core.mcp.ElicitAnswer("accept", content))
        assertEquals("""accept:{"age": 19, "color": "g", "name": "Farrel"}""", call.await().output)
        assertTrue(m.interactions.value.isEmpty())

        // Sampling asks first; "Always" skips the question next time.
        val s1 = scope.async { m.callTool(think, JsonObject(emptyMap()), out) { it.name } }
        val ap = kotlinx.coroutines.withTimeout(10_000) { m.interactions.first { it.isNotEmpty() } }.first() as dev.harness.core.mcp.McpInteraction.Sampling
        assertTrue(ap.preview.contains("2+2?"))
        m.answerSampling(ap.id, dev.harness.core.mcp.SamplingDecision.ALWAYS)
        assertEquals("4 (math, 50)", s1.await().output)
        assertEquals("4 (math, 50)", m.callTool(think, JsonObject(emptyMap()), out) { it.name }.output)
        m.closeAll()
    }
}
