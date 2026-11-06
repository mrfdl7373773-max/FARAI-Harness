package dev.harness

import dev.harness.core.ConfirmationGate
import dev.harness.core.ContextCompactor
import dev.harness.core.Engine
import dev.harness.core.EngineEvent
import dev.harness.core.PersonaLoader
import dev.harness.core.RepoMapper
import dev.harness.core.SessionState
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatMessage
import dev.harness.net.FunctionCall
import dev.harness.net.Role
import dev.harness.net.ToolCall
import dev.harness.tools.ToolRegistry
import dev.harness.tools.Workspace
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompactorTest {

    private fun history(n: Int) = (1..n).flatMap {
        listOf(ChatMessage(Role.USER, "question $it " + "x".repeat(400)), ChatMessage(Role.ASSISTANT, "answer $it " + "y".repeat(400)))
    }

    @Test
    fun thresholdMath() {
        val repo = TestSupport.repo()
        val c = ContextCompactor(FakeChatClient(), PersonaLoader(repo))
        assertFalse(c.shouldCompact(799, 1000, 0.8f))
        assertTrue(c.shouldCompact(800, 1000, 0.8f))
    }

    @Test
    fun compactionProducesSummaryMessageAndKeepsRecent() = runTest {
        val repo = TestSupport.repo()
        val client = FakeChatClient(FakeChatClient.text("## Session summary so far\nTask: testing"))
        val c = ContextCompactor(client, PersonaLoader(repo))
        val msgs = history(10)
        val r = c.compact(msgs, "m", keepRecent = 4)!!
        assertTrue(r.messages.first().content!!.startsWith("<conversation-checkpoint>\n## Session summary so far\nTask: testing\n</conversation-checkpoint>"))
        assertEquals("## Session summary so far\nTask: testing", r.summary)
        assertEquals(Role.USER, r.messages.first().role)
        assertEquals(msgs.takeLast(4), r.messages.drop(1))
        assertEquals(16, r.removedCount)
        // The summariser is instructed by compaction.md.
        assertEquals(repo.read("compaction.md"), client.requests.single().messages.first().content)
    }

    @Test
    fun splitNeverOrphansToolResults() {
        val repo = TestSupport.repo()
        val c = ContextCompactor(FakeChatClient(), PersonaLoader(repo))
        val msgs = listOf(
            ChatMessage(Role.USER, "a"),
            ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("t", function = FunctionCall("glob", "{}")))),
            ChatMessage(Role.TOOL, "r", toolCallId = "t"),
            ChatMessage(Role.ASSISTANT, "done"),
        )
        assertEquals(1, c.splitPoint(msgs, keepRecent = 2))
    }

    @Test
    fun engineTriggersCompactionAtThreshold() = runTest {
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        val client = FakeChatClient(FakeChatClient.text("## Session summary so far\ncompressed"), FakeChatClient.text("fresh answer"))
        val settings = AppSettings().let { it.copy(provider = it.provider.copy(contextWindow = 2000), agent = it.agent.copy(contextThreshold = 0.5f)) }
        val ws = Workspace(File(TestSupport.tempDir("ws"), "workspace"))
        val engine = Engine(client, persona, ToolRegistry(ToolRegistry.core()), ContextCompactor(client, persona), RepoMapper(), ws,
            OkHttpClient(), { settings }, { TestSupport.env(it) })
        val state = SessionState("c1", messages = history(10))
        val events = mutableListOf<EngineEvent>()
        engine.run(state, "next", ConfirmationGate { _, _ -> true }) { events += it }
        assertTrue(events.any { it is EngineEvent.Compacted })
        assertTrue(state.messages.first().content!!.contains("## Session summary so far\ncompressed"))
        assertTrue(client.requests[1].messages.any { it.content?.contains("## Session summary so far\ncompressed") == true })
    }
}
