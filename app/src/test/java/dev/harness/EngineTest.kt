package dev.harness

import dev.harness.core.ConfirmationGate
import dev.harness.core.ContextCompactor
import dev.harness.core.Engine
import dev.harness.core.EngineEvent
import dev.harness.core.PersonaLoader
import dev.harness.core.RepoMapper
import dev.harness.core.RunOutcome
import dev.harness.core.SessionState
import dev.harness.data.settings.AppSettings
import dev.harness.net.Role
import dev.harness.tools.ToolRegistry
import dev.harness.tools.Workspace
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineTest {

    private fun engine(client: FakeChatClient, settings: AppSettings = AppSettings()): Pair<Engine, Workspace> {
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        val ws = Workspace(File(TestSupport.tempDir("ws"), "workspace"))
        val e = Engine(
            client = client, persona = persona, tools = ToolRegistry(ToolRegistry.core()),
            compactor = ContextCompactor(client, persona), repoMapper = RepoMapper(), workspace = ws,
            http = OkHttpClient(), settings = { settings }, environment = { TestSupport.env(it, ws.root.path) },
        )
        return e to ws
    }

    private val allow = ConfirmationGate { _, _ -> true }

    @Test
    fun toolCallRoundTripThenFinish() = runTest {
        val client = FakeChatClient(
            FakeChatClient.tool("c1", "write", """{"path":"hello.txt","content":"hi there"}"""),
            FakeChatClient.tool("c2", "read", """{"path":"hello.txt"}"""),
            FakeChatClient.tool("c3", "finish", """{"summary":"Wrote hello.txt"}"""),
        )
        val (engine, ws) = engine(client)
        val state = SessionState("s1")
        val events = mutableListOf<EngineEvent>()

        val outcome = engine.run(state, "create hello.txt", allow) { events += it }

        assertEquals(RunOutcome.FINISHED, outcome)
        assertEquals("hi there", File(ws.root, "hello.txt").readText())
        assertEquals(3, client.requests.size)
        // Second request carries the write result back to the model.
        val second = client.requests[1].messages
        assertTrue(second.any { it.role == Role.TOOL && it.toolCallId == "c1" && it.content!!.startsWith("created") })
        // Third request carries the numbered read result.
        assertTrue(client.requests[2].messages.any { it.role == Role.TOOL && it.toolCallId == "c2" && it.content!!.contains("1: hi there") })
        assertTrue(events.any { it is EngineEvent.Finished && it.summary == "Wrote hello.txt" })
        assertTrue(state.finished)
        // Every request starts with the system message built from prompt files.
        assertTrue(client.requests.all { it.messages.first().role == Role.SYSTEM && it.messages.first().content!!.contains("You are FARAI") })
        // Tool descriptions are the prompt files' text.
        val bash = client.requests[0].tools!!.first { it.function.name == "bash" }
        assertTrue(bash.function.description.startsWith("Run a shell command"))
    }

    @Test
    fun textOnlyReplyStopsAndStreams() = runTest {
        val client = FakeChatClient(FakeChatClient.text("Hello Farrel"))
        val (engine, _) = engine(client)
        val deltas = StringBuilder()
        val outcome = engine.run(SessionState("s2"), "hi", allow) { if (it is EngineEvent.AssistantDelta) deltas.append(it.text) }
        assertEquals(RunOutcome.REPLIED, outcome)
        assertEquals("Hello Farrel", deltas.toString())
    }

    @Test
    fun unknownToolAndBadJsonBecomeErrorResults() = runTest {
        val client = FakeChatClient(
            FakeChatClient.tool("a", "nope", "{}"),
            FakeChatClient.tool("b", "read", "{not json"),
            FakeChatClient.text("ok"),
        )
        val (engine, _) = engine(client)
        val state = SessionState("s3")
        engine.run(state, "x", allow) { }
        val results = state.messages.filter { it.role == Role.TOOL }.map { it.content!! }
        assertTrue(results[0].startsWith("ERROR: unknown tool"))
        assertTrue(results[1].startsWith("ERROR: arguments are not valid JSON"))
    }

    @Test
    fun maxTurnsIsEnforced() = runTest {
        val loops = Array(5) { FakeChatClient.tool("t$it", "glob", """{"pattern":"*"}""") }
        val client = FakeChatClient(*loops)
        val s = AppSettings().let { it.copy(provider = it.provider.copy(maxTurns = 3)) }
        val (engine, _) = engine(client, s)
        val outcome = engine.run(SessionState("s4"), "loop", allow) { }
        assertEquals(RunOutcome.TURN_LIMIT, outcome)
        // Three steps plus one final summary request without tools.
        assertEquals(4, client.requests.size)
        assertTrue(client.requests.last().tools == null)
    }

    @Test
    fun subagentGetsFreshContextAndReadOnlyTools() = runTest {
        val client = FakeChatClient(
            FakeChatClient.tool("p1", "task", """{"description":"find files","prompt":"List the files"}"""),
            FakeChatClient.tool("s1", "glob", """{"pattern":"**/*"}"""),
            FakeChatClient.text("Found nothing"),
            FakeChatClient.text("Done"),
        )
        val (engine, _) = engine(client)
        val events = mutableListOf<EngineEvent>()
        val state = SessionState("s5")
        engine.run(state, "explore", allow) { events += it }

        val subRequest = client.requests[1]
        assertEquals(2, subRequest.messages.size) // system + subagent prompt only
        assertEquals("List the files", subRequest.messages[1].content)
        assertEquals(setOf("read", "glob", "grep", "bash", "web_fetch", "web_search", "skill"), subRequest.tools!!.map { it.function.name }.toSet())
        assertTrue(events.any { it is EngineEvent.ToolStarted && it.name == "glob" && it.parentCallId == "p1" })
        assertTrue(state.messages.any { it.role == Role.TOOL && it.toolCallId == "p1" && it.content!!.startsWith("Found nothing") && it.content!!.contains("<task_id>") })
    }

    @Test
    fun disabledToolIsNotOffered() = runTest {
        val client = FakeChatClient(FakeChatClient.text("ok"))
        val (engine, _) = engine(client)
        val state = SessionState("s6", disabledTools = mutableSetOf("bash"))
        engine.run(state, "x", allow) { }
        assertTrue(client.requests[0].tools!!.none { it.function.name == "bash" })
    }
}
