package dev.harness.core

import dev.harness.data.settings.SafetySettings

sealed class SafetyDecision {
    data object Allowed : SafetyDecision()
    data class Blocked(val rule: String, val reason: String) : SafetyDecision()
    data class NeedsConfirmation(val rule: String) : SafetyDecision()
}

/** Asks the human to approve an action. The UI shows a dialog; tests can auto-answer. */
fun interface ConfirmationGate {
    suspend fun confirm(title: String, detail: String): Boolean

    /** Richer answer (once / always / deny with feedback). Screens override this; tests use [confirm]. */
    suspend fun decide(request: dev.harness.core.permission.ApprovalRequest): dev.harness.core.permission.Approval =
        if (confirm(request.title, request.detail)) dev.harness.core.permission.Approval.Once
        else dev.harness.core.permission.Approval.Deny()
}

/** A multiple-choice question from the model (the `question` tool). */
data class Question(
    val question: String,
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean = false,
)

data class QuestionOption(val label: String, val description: String = "")

/** Shows questions to the user and returns one answer list per question (null = dismissed). */
fun interface QuestionGate {
    suspend fun ask(questions: List<Question>): List<List<String>>?
}

/**
 * Command policy, driven entirely by the user's lists in Settings → Safety.
 *
 * - blocklist: a command containing any entry is refused. `/regex/` entries are regexes.
 * - allowlist: when non-empty, every sub-command (split on ; && || | newlines and $( ) / backticks)
 *   must start with an allowlisted entry. When empty, everything not blocked is allowed.
 * - confirm list: a command containing an entry needs the user's approval. Entries of the form
 *   `tool:<name>` require approval before that tool runs at all.
 *
 * Emptying all three lists removes every restriction. There is no hidden policy.
 */
class Safety(private val settings: SafetySettings) {

    fun check(command: String): SafetyDecision {
        val cmd = command.trim()
        for (rule in settings.blocklist.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("tool:") }) {
            if (matches(rule, cmd)) return SafetyDecision.Blocked(rule, "matches blocklist entry \"$rule\"")
        }
        val allow = settings.allowlist.map { it.trim() }.filter { it.isNotEmpty() }
        if (allow.isNotEmpty()) {
            for (segment in segments(cmd)) {
                val ok = allow.any { entry -> startsWithEntry(segment, entry) }
                if (!ok) {
                    val head = segment.split(Regex("\\s+")).firstOrNull().orEmpty()
                    return SafetyDecision.Blocked("allowlist", "\"$head\" is not in the allowlist")
                }
            }
        }
        for (rule in settings.confirmList.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("tool:") }) {
            if (matches(rule, cmd)) return SafetyDecision.NeedsConfirmation(rule)
        }
        return SafetyDecision.Allowed
    }

    fun toolBlocked(toolName: String): Boolean =
        settings.blocklist.any { it.trim() == "tool:$toolName" }

    fun toolNeedsConfirmation(toolName: String): Boolean =
        settings.confirmList.any { it.trim() == "tool:$toolName" }

    private fun matches(rule: String, cmd: String): Boolean =
        if (rule.length > 2 && rule.startsWith("/") && rule.endsWith("/")) {
            runCatching { Regex(rule.substring(1, rule.length - 1)).containsMatchIn(cmd) }.getOrDefault(false)
        } else cmd.contains(rule)

    private fun startsWithEntry(segment: String, entry: String): Boolean {
        if (entry.length > 2 && entry.startsWith("/") && entry.endsWith("/")) return matches(entry, segment)
        if (!segment.startsWith(entry)) return false
        return segment.length == entry.length || segment[entry.length].isWhitespace()
    }

    companion object {
        /** Splits a shell command into the commands it would run. */
        fun segments(command: String): List<String> {
            val expanded = command
                .replace(Regex("\\$\\(([^)]*)\\)")) { "\n" + it.groupValues[1] + "\n" }
                .replace(Regex("`([^`]*)`")) { "\n" + it.groupValues[1] + "\n" }
            return expanded.split(Regex("&&|\\|\\||;|\\||\\n|&"))
                .map { it.trim().trimStart('(', '{').trim() }
                .map { stripEnvAssignments(it) }
                .filter { it.isNotEmpty() }
        }

        private fun stripEnvAssignments(seg: String): String {
            var s = seg
            val assign = Regex("^[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+")
            while (true) {
                val m = assign.find(s) ?: break
                s = s.substring(m.range.last + 1)
            }
            return s.trim()
        }
    }
}
