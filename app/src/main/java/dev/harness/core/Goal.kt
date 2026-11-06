package dev.harness.core

import dev.harness.net.HarnessJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * `/goal <objective>`: the agent keeps working across turns until a separate reviewer call,
 * judging only evidence in the conversation, says the objective is met (or the agent is blocked,
 * stalls, or runs out of rounds). Prompts: goal/kickoff.md, goal/continuation.md, goal/judge.md.
 */
@Serializable
data class Goal(
    val objective: String,
    val status: String = ACTIVE,
    val rounds: Int = 0,
    val stalls: Int = 0,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == ACTIVE

    companion object {
        const val ACTIVE = "active"
        const val PAUSED = "paused"
        const val COMPLETE = "complete"
        const val MAX_OBJECTIVE = 4000
    }
}

@Serializable
data class GoalVerdict(val verdict: String, val reason: String = "", val next_step: String? = null) {
    companion object {
        /** Finds the first JSON object with a "verdict" in a reply that may carry extra text. */
        fun parse(text: String): GoalVerdict? {
            val candidates = Regex("\\{[^{}]*\"verdict\"[^{}]*\\}").findAll(text).map { it.value }.toList()
            for (c in candidates) runCatching { return HarnessJson.decodeFromString<GoalVerdict>(c) }
            val word = Regex("\\b(done|continue|blocked|paused)\\b", RegexOption.IGNORE_CASE).find(text)?.value?.lowercase()
            return word?.let { GoalVerdict(it, text.take(300)) }
        }
    }
}

/** One small JSON file per session (goals outlive app restarts). */
class GoalStore(private val dir: File) {
    private fun file(sessionId: String) = File(dir, sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun get(sessionId: String): Goal? = runCatching { HarnessJson.decodeFromString<Goal>(file(sessionId).readText()) }.getOrNull()

    fun set(sessionId: String, goal: Goal?) {
        val f = file(sessionId)
        if (goal == null) { f.delete(); return }
        dir.mkdirs()
        f.writeText(HarnessJson.encodeToString(goal))
    }
}
