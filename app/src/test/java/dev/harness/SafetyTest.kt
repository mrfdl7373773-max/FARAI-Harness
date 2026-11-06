package dev.harness

import dev.harness.core.Safety
import dev.harness.core.SafetyDecision
import dev.harness.data.settings.SafetySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTest {

    @Test
    fun allowlistBlocksNonAllowlistedCommand() {
        val s = Safety(SafetySettings(allowlist = listOf("ls", "cat"), blocklist = emptyList(), confirmList = emptyList()))
        assertEquals(SafetyDecision.Allowed, s.check("ls -la"))
        assertTrue(s.check("curl http://example.com") is SafetyDecision.Blocked)
        // Chained commands are each checked.
        assertTrue(s.check("ls && rm file") is SafetyDecision.Blocked)
        assertTrue(s.check("cat x | sh") is SafetyDecision.Blocked)
        assertTrue(s.check("echo $(whoami)") is SafetyDecision.Blocked)
        // Prefix must be a whole word.
        assertTrue(s.check("lsblk") is SafetyDecision.Blocked)
    }

    @Test
    fun blocklistAndRegex() {
        val s = Safety(SafetySettings(allowlist = emptyList(), blocklist = listOf("rm -rf /", "/\\breboot\\b/"), confirmList = emptyList()))
        assertTrue(s.check("rm -rf / --no-preserve-root") is SafetyDecision.Blocked)
        assertTrue(s.check("sudo reboot now") is SafetyDecision.Blocked)
        assertEquals(SafetyDecision.Allowed, s.check("echo rebooting"))
    }

    @Test
    fun confirmListAsksAndToolRules() {
        val s = Safety(SafetySettings(allowlist = emptyList(), blocklist = listOf("tool:web_fetch"), confirmList = listOf("rm ", "tool:write")))
        assertTrue(s.check("rm notes.txt") is SafetyDecision.NeedsConfirmation)
        assertTrue(s.toolNeedsConfirmation("write"))
        assertTrue(s.toolBlocked("web_fetch"))
        assertEquals(SafetyDecision.Allowed, s.check("ls"))
    }

    @Test
    fun emptyListsMeanNoRestrictions() {
        val s = Safety(SafetySettings(emptyList(), emptyList(), emptyList()))
        assertEquals(SafetyDecision.Allowed, s.check("rm -rf /"))
    }

    @Test
    fun envAssignmentsAreSkippedWhenMatchingAllowlist() {
        val s = Safety(SafetySettings(allowlist = listOf("ls"), blocklist = emptyList(), confirmList = emptyList()))
        assertEquals(SafetyDecision.Allowed, s.check("LANG=C ls"))
    }
}
