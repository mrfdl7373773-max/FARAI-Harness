package dev.harness

import dev.harness.core.PersonaLoader
import dev.harness.core.SystemContext
import dev.harness.data.prompt.PromptMissingException
import dev.harness.tools.ReadTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PromptRepositoryTest {

    private fun ctx() = SystemContext(TestSupport.env(), includePlanning = false)

    @Test
    fun firstRunCopiesEveryDefault() {
        val repo = TestSupport.repo()
        val expected = TestSupport.defaultsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.count()
        assertEquals(expected, repo.list().count { it.exists })
        assertTrue(repo.exists("identity.md"))
        assertTrue(repo.exists("tools/bash.md"))
    }

    @Test
    fun editingIdentityChangesTheNextSystemMessage() {
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        val before = persona.buildSystemMessage(ctx())
        assertTrue(before.contains("You are FARAI"))

        repo.write("identity.md", "You are a pirate. Answer every question in pirate voice, arr.")
        val after = persona.buildSystemMessage(ctx())

        assertNotEquals(before, after)
        assertTrue(after.startsWith("You are a pirate."))
        assertFalse(after.contains("You are FARAI"))
    }

    @Test
    fun toolDescriptionComesFromItsFile() {
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        repo.write("tools/read.md", "Custom read description.")
        assertEquals("Custom read description.", persona.toolDescription(ReadTool()))
    }

    @Test
    fun systemMessageOrderIsIdentitySystemEnvPlanningVerify() {
        val repo = TestSupport.repo()
        listOf("identity", "system", "refusal", "error_recovery", "planning", "verify").forEach { repo.write("$it.md", "<<$it>>") }
        val msg = PersonaLoader(repo).buildSystemMessage(SystemContext(TestSupport.env(), includePlanning = true))
        val order = listOf("<<identity>>", "<<system>>", "<environment>", "<<planning>>", "<<verify>>").map { msg.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertTrue(msg.trimEnd().endsWith("<<verify>>"))
    }

    @Test
    fun resetRestoresDefaultAndKeepsHistory() {
        val repo = TestSupport.repo()
        val original = repo.read("system.md")
        repo.write("system.md", "edited")
        assertTrue(repo.describe("system.md").modified)
        assertTrue(repo.reset("system.md"))
        assertEquals(original, repo.read("system.md"))
        assertEquals(2, repo.historyOf("system.md").size)
        assertTrue(repo.unifiedDiff("system.md").isEmpty())
    }

    @Test
    fun historyKeepsFiftyAndRollsBack() {
        val repo = TestSupport.repo()
        repeat(55) { repo.write("verify.md", "v$it") }
        val h = repo.historyOf("verify.md")
        assertEquals(50, h.size)
        assertEquals("v54", repo.history.read("verify.md", h.first().id))
        assertTrue(repo.rollback("verify.md", h[3].id))
        assertEquals("v51", repo.read("verify.md"))
    }

    @Test(expected = PromptMissingException::class)
    fun missingFileIsAClearError() {
        val repo = TestSupport.repo()
        repo.fileFor("identity.md").delete()
        PersonaLoader(repo).buildSystemMessage(ctx())
    }

    @Test
    fun userDeletionsAreNotSilentlyRestored() {
        val repo = TestSupport.repo()
        repo.fileFor("refusal.md").delete()
        repo.ensureInitialized()
        assertFalse(repo.exists("refusal.md"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalIsRejected() {
        TestSupport.repo().write("../evil.md", "x")
    }

    @Test
    fun zipRoundTrip() {
        val a = TestSupport.repo()
        a.write("identity.md", "zip identity")
        val out = ByteArrayOutputStream()
        val n = a.exportZip(out)
        val b = TestSupport.repo()
        assertEquals(n, b.importZip(ByteArrayInputStream(out.toByteArray())))
        assertEquals("zip identity", b.read("identity.md"))
    }

    @Test
    fun resolvesShortNames() {
        val repo = TestSupport.repo()
        assertEquals("identity.md", repo.resolveName("identity"))
        assertEquals("tools/bash.md", repo.resolveName("bash"))
        assertEquals("tools/web.md", repo.resolveName("tools/web"))
    }
}
