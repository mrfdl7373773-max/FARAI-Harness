package dev.harness

import dev.harness.data.settings.ProviderSettings
import dev.harness.net.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortTest {
    @Test fun normalizesAliases() {
        assertEquals("xhigh", ReasoningEffort.normalize("extra-high"))
        assertEquals("medium", ReasoningEffort.normalize("MED"))
        assertEquals("none", ReasoningEffort.normalize("off"))
        assertNull(ReasoningEffort.normalize("banana"))
    }

    @Test fun shapesPerProvider() {
        val or = ProviderSettings(baseUrl = "https://openrouter.ai/api/v1", model = "x", reasoningEffort = "max")
        assertTrue(ReasoningEffort.apply("""{"model":"x"}""", or, ReasoningEffort.forRequest(or)).contains(""""reasoning":{"effort":"max"}"""))
        val oa = ProviderSettings(baseUrl = "https://api.openai.com/v1", model = "gpt-5.5", reasoningEffort = "high")
        assertTrue(ReasoningEffort.apply("""{"model":"x"}""", oa, ReasoningEffort.forRequest(oa)).contains(""""reasoning_effort":"high""""))
        val none = ProviderSettings(reasoningEffort = "")
        assertEquals("""{"model":"x"}""", ReasoningEffort.apply("""{"model":"x"}""", none, ReasoningEffort.forRequest(none)))
    }

    @Test fun clampsKnownProviders() {
        val gem = ProviderSettings(baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", model = "gemini-3-flash", reasoningEffort = "max")
        assertEquals("high", ReasoningEffort.forRequest(gem))
        val claude = ProviderSettings(baseUrl = "https://api.anthropic.com/v1", model = "claude", reasoningEffort = "minimal")
        assertEquals("low", ReasoningEffort.forRequest(claude))
    }

    @Test fun picksNearestFromError() {
        val body = """{"error":{"message":"Invalid reasoning_effort: minimal. Valid values are: high, low, medium, none"}}"""
        assertEquals(listOf("high", "low", "medium", "none"), ReasoningEffort.allowedFrom(body))
        assertEquals("low", ReasoningEffort.nearest("minimal", ReasoningEffort.allowedFrom(body)))
        assertEquals("high", ReasoningEffort.nearest("max", listOf("low", "medium", "high")))
        assertNull(ReasoningEffort.nearest("high", emptyList()))
    }

    @Test fun levelsFollowTheModel() {
        val m = ReasoningEffort::modelLevels
        assertEquals("high", m("openai/gpt-5-mini")!!.last())
        assertEquals("minimal", m("gpt-5")!!.first())
        assertEquals("xhigh", m("gpt-5.2")!!.last())
        assertEquals(listOf("none", "low", "medium", "high"), m("gpt-5.1"))
        assertEquals("max", m("anthropic/claude-opus-4.6")!!.last())
        assertEquals("max", m("claude-opus-4-7")!!.last())
        assertTrue("xhigh" in m("claude-opus-4-7")!!)
        assertEquals("high", m("claude-sonnet-4-20250514")!!.last())
        assertEquals("high", m("anthropic/claude-3.7-sonnet:thinking")!!.last())
        assertTrue(m("claude-3-5-sonnet-20241022")!!.isEmpty())
        assertTrue(m("openai/gpt-4o")!!.isEmpty())
        assertEquals(listOf("low", "high"), m("google/gemini-3-pro-preview"))
        assertEquals("high", m("o4-mini")!!.last())
        assertNull(m("some-new-model"))
    }

    @Test fun requestClampsToModel() {
        val gpt = ProviderSettings(baseUrl = "https://openrouter.ai/api/v1", model = "openai/gpt-5.1", reasoningEffort = "max")
        assertEquals("high", ReasoningEffort.forRequest(gpt))
        val plain = ProviderSettings(baseUrl = "https://api.openai.com/v1", model = "gpt-4o", reasoningEffort = "high")
        assertNull(ReasoningEffort.forRequest(plain))
    }
}
