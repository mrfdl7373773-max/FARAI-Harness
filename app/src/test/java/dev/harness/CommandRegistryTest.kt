package dev.harness

import dev.harness.core.PersonaLoader
import dev.harness.core.PromptRepository
import dev.harness.core.SessionState
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandRegistry
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.NavTarget
import dev.harness.core.commands.SessionSummary
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatMessage
import dev.harness.net.Role
import dev.harness.tools.ToolRegistry
import dev.harness.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRegistryTest {

    private class FakeContext(override val prompts: PromptRepository) : CommandContext {
        override val registry = CommandRegistry()
        override val persona = PersonaLoader(prompts)
        override val tools = ToolRegistry(ToolRegistry.core())
        override val agents = dev.harness.core.agents.AgentStore(prompts) { null }
        override val state = SessionState("fake", listOf(ChatMessage(Role.USER, "hello"), ChatMessage(Role.ASSISTANT, "hi")))
        var settings = AppSettings()
        var title = "Test"
        var tags = listOf<String>()
        var saved = 0
        override suspend fun settings() = settings
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { settings = transform(settings) }
        override suspend fun sessionTitle() = title
        override suspend fun renameSession(title: String) { this.title = title }
        override suspend fun saveSession() { saved++ }
        override suspend fun forkSession(upToMessages: Int?) = "fork-id"
        override suspend fun sessionTags() = tags
        override suspend fun setSessionTags(tags: List<String>) { this.tags = tags.distinct() }
        override suspend fun searchSessions(query: String) = listOf(SessionSummary("abc123", "Old", 0, 2, listOf("x"), "m"))
        override suspend fun sessionsByTag(tag: String) = listOf(SessionSummary("abc123", "Old", 0, 2, listOf(tag), "m"))
        override suspend fun transcript() = "# Test"
        override suspend fun clearContext() { state.messages.clear() }
        override suspend fun resetSession() { state.messages.clear(); state.agent = "build" }
        override suspend fun systemMessagePreview() = persona.buildSystemMessage(dev.harness.core.SystemContext(TestSupport.env(), includePlanning = false))
        override fun estimateTokens() = 42
        override suspend fun compactNow(): String? = "summary"
        override suspend fun listModels() = listOf("test-model")
        override suspend fun healthCheck() = "ok"
        override suspend fun runTool(name: String, argumentsJson: String) = ToolResult("ran $name")
        override suspend fun retryLast() = true
        override suspend fun undoLast() = 2
    }

    private val sampleArgs = mapOf(
        "prompt" to "show identity", "rename" to "New title", "model" to "other-model", "mode" to "planning",
        "tool" to "disable bash", "run" to "glob {\"pattern\":\"*\"}", "tag" to "add demo", "sessions" to "Old",
        "template" to "list", "temp" to "0.5", "turns" to "12", "help" to "prompt", "fork" to "", "history" to "demo",
    )

    @Test
    fun registryHasRequiredRecommendedAndAtLeastEightMore() {
        val r = CommandRegistry()
        val required = listOf("help", "prompt", "config", "quit")
        val recommended = listOf("model", "todo", "clear", "save", "export", "tools", "mode", "reset-all")
        (required + recommended).forEach { assertNotNull("missing /$it", r.resolve(it)) }
        assertTrue(r.all.size - required.size - recommended.size >= 8)
    }

    @Test
    fun everyCommandResolvesAndDispatches() = runTest {
        val ctx = FakeContext(TestSupport.repo())
        for (c in ctx.registry.all) {
            assertEquals(c, ctx.registry.resolve(c.name))
            c.aliases.forEach { assertEquals(c, ctx.registry.resolve(it)) }
            val line = "/${c.name} ${sampleArgs[c.name] ?: ""}".trim()
            val result = ctx.registry.dispatch(line, ctx)
            assertFalse("/${c.name} returned error: $result", result is CommandResult.Error && result.text.contains("failed"))
        }
    }

    @Test
    fun helpListsEveryCommand() = runTest {
        val ctx = FakeContext(TestSupport.repo())
        val out = (ctx.registry.dispatch("/help", ctx) as CommandResult.Output).text
        ctx.registry.all.forEach { assertTrue("help is missing /${it.name}", out.contains("/${it.name}")) }
    }

    @Test
    fun promptSubcommandsHitTheRepository() = runTest {
        val repo = TestSupport.repo()
        val ctx = FakeContext(repo)
        repo.write("identity.md", "pirate")
        val diff = ctx.registry.dispatch("/prompt diff identity", ctx) as CommandResult.Output
        assertTrue(diff.text.contains("+pirate"))
        assertEquals(NavTarget.PromptEditor("identity.md"), (ctx.registry.dispatch("/prompt edit identity", ctx) as CommandResult.Navigate).target)
        ctx.registry.dispatch("/prompt reset identity", ctx)
        assertTrue(repo.read("identity.md").contains("You are FARAI"))
    }

    @Test
    fun stateChangingCommands() = runTest {
        val ctx = FakeContext(TestSupport.repo())
        ctx.registry.dispatch("/model other-model", ctx)
        assertEquals("other-model", ctx.settings.provider.model)
        ctx.registry.dispatch("/mode verify", ctx)
        assertEquals("verify", ctx.state.agent)
        ctx.registry.dispatch("/tool disable bash", ctx)
        assertTrue("bash" in ctx.state.disabledTools)
        ctx.registry.dispatch("/rename Shiny title", ctx)
        assertEquals("Shiny title", ctx.title)
        ctx.registry.dispatch("/clear", ctx)
        assertTrue(ctx.state.messages.isEmpty())
        assertTrue(ctx.registry.dispatch("/template run bugfix crash on start", ctx) is CommandResult.SendToAgent)
        assertTrue("tpl-bugfix" in ctx.tags)
    }

    @Test
    fun unknownCommandSuggests() = runTest {
        val ctx = FakeContext(TestSupport.repo())
        val r = ctx.registry.dispatch("/hepl", ctx) as CommandResult.Error
        assertTrue(r.text.contains("/help"))
        assertTrue(ctx.registry.suggestions("/pro").any { it.label == "/prompt" })
        assertTrue(ctx.registry.suggestions("/prompt d").any { it.label == "/prompt diff" })
    }

    @Test
    fun tokenizerHonoursQuotes() {
        assertEquals(listOf("add", "two words", "x"), CommandRegistry.tokenize("add \"two words\" x"))
    }
}
