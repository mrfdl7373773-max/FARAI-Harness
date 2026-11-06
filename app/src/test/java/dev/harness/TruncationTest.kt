package dev.harness

import dev.harness.core.ConfirmationGate
import dev.harness.core.ContextCompactor
import dev.harness.core.Engine
import dev.harness.core.PersonaLoader
import dev.harness.core.RepoMapper
import dev.harness.core.SessionState
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatResult
import dev.harness.net.FunctionCall
import dev.harness.net.MaxTokensFallback
import dev.harness.net.Role
import dev.harness.net.ToolCall
import dev.harness.tools.ToolRegistry
import dev.harness.tools.Workspace
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TruncationTest {
    private val allow = ConfirmationGate { _, _ -> true }

    private fun engine(client: FakeChatClient): Pair<Engine, Workspace> {
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        val ws = Workspace(File(TestSupport.tempDir("ws"), "workspace"))
        return Engine(
            client = client, persona = persona, tools = ToolRegistry(ToolRegistry.core()),
            compactor = ContextCompactor(client, persona), repoMapper = RepoMapper(), workspace = ws,
            http = OkHttpClient(), settings = { AppSettings() }, environment = { TestSupport.env(it, ws.root.path) },
        ) to ws
    }

    @Test
    fun cutOffToolCallIsNotRunAndModelIsTold() = runTest {
        val cut = ChatResult("", listOf(ToolCall("c1", function = FunctionCall("write", """{"path":"big.py","content":"print(1)\nprint("""))), "length")
        val client = FakeChatClient(
            cut,
            FakeChatClient.tool("c2", "write", """{"path":"big.py","content":"a\n"}"""),
            FakeChatClient.tool("c3", "write", """{"path":"big.py","content":"b\n","append":true}"""),
            FakeChatClient.text("done"),
        )
        val (engine, ws) = engine(client)
        val state = SessionState("t")
        engine.run(state, "x", allow) { }
        val first = state.messages.first { it.role == Role.TOOL && it.toolCallId == "c1" }.content!!
        assertTrue(first, first.contains("output token cap"))
        assertEquals("a\nb\n", File(ws.root, "big.py").readText())
    }

    @Test
    fun maxTokensCapIsReadFromError() {
        val err = """{"error":{"message":"max_tokens: 16384 > 8192, which is the maximum allowed number of output tokens"}}"""
        assertTrue(MaxTokensFallback.isMaxTokensError(err))
        assertEquals(8192, MaxTokensFallback.capFrom(err, 16384))
        assertNull(MaxTokensFallback.capFrom("max_tokens too large", 16384))
        val body = MaxTokensFallback.withMaxTokens("""{"model":"m","max_tokens":16384}""", null)
        assertFalse(body.contains("max_tokens"))
        assertEquals(8192, MaxTokensFallback.requested(MaxTokensFallback.withMaxTokens(body, 8192)))
    }

    @Test
    fun defaultMaxTokensIsRaised() {
        assertEquals(16_384, AppSettings().provider.maxTokens)
    }
}
