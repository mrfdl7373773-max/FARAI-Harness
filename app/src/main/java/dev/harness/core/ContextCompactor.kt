package dev.harness.core

import dev.harness.net.ChatClient
import dev.harness.net.ChatMessage
import dev.harness.net.ChatRequest
import dev.harness.net.Role

data class CompactionResult(
    val messages: List<ChatMessage>,
    val summary: String,
    val removedCount: Int,
)

/**
 * Summarises older turns with the instructions in prompts/compaction.md once the estimated
 * context use passes the configured threshold. Recent turns are kept verbatim; the todo list
 * is kept separately in the environment block.
 */
class ContextCompactor(
    private val client: ChatClient,
    private val persona: PersonaLoader,
) {

    fun estimateTokens(systemMessage: String, messages: List<ChatMessage>): Int {
        var chars = systemMessage.length
        for (m in messages) {
            chars += (m.content?.length ?: 0) + 16
            m.toolCalls?.forEach { chars += it.function.name.length + it.function.arguments.length + 24 }
        }
        return chars / 4 + 1
    }

    fun shouldCompact(estimatedTokens: Int, contextWindow: Int, threshold: Float): Boolean =
        contextWindow > 0 && estimatedTokens >= (contextWindow * threshold.coerceIn(0.05f, 1f)).toInt()

    /** Index of the first message to keep; never splits an assistant tool call from its results. */
    fun splitPoint(messages: List<ChatMessage>, keepRecent: Int): Int {
        var split = (messages.size - keepRecent).coerceAtLeast(0)
        while (split > 0 && messages[split].role == Role.TOOL) split--
        return split
    }

    suspend fun compact(messages: List<ChatMessage>, model: String, keepRecent: Int = DEFAULT_KEEP): CompactionResult? {
        val split = splitPoint(messages, keepRecent)
        if (split <= 0) return null
        val older = messages.subList(0, split)
        val recent = messages.subList(split, messages.size)
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(Role.SYSTEM, persona.compactionInstructions()),
                ChatMessage(Role.USER, transcript(older)),
            ),
            temperature = 0.1f,
            stream = false,
        )
        val summary = client.chat(request).content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            .removePrefix(ChatText.CHECKPOINT_OPEN).removeSuffix("</conversation-checkpoint>").trim()
        if (summary.isEmpty()) return null
        val wrapped = "${ChatText.CHECKPOINT_OPEN}\n$summary\n</conversation-checkpoint>\n\n" +
            "(Earlier conversation was summarised above to save context. The newest messages follow verbatim. Continue the work; do not mention the summary.)"
        return CompactionResult(listOf(ChatMessage(Role.USER, wrapped)) + recent, summary, older.size)
    }

    /**
     * Clears the output of old tool results, newest [protectChars] characters of tool output kept.
     * Cheap first step before a summary. Returns how many results were cleared.
     */
    fun prune(messages: MutableList<ChatMessage>, protectChars: Int = PRUNE_PROTECT): Int {
        var budget = protectChars
        var cleared = 0
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.role != Role.TOOL) continue
            val len = m.content?.length ?: 0
            if (budget > 0) { budget -= len; continue }
            if (len <= PRUNE_MIN || m.content?.startsWith(PRUNED) == true) continue
            messages[i] = m.copy(content = "$PRUNED ${m.name ?: "tool"} returned ${len} characters here; run it again if you need them]")
            cleared++
        }
        return cleared
    }

    /** Plain-text rendering of messages for the summariser. */
    fun transcript(messages: List<ChatMessage>): String = buildString {
        for (m in messages) {
            when (m.role) {
                Role.USER -> if (ChatText.isCheckpoint(m.content)) append("PREVIOUS CHECKPOINT (update it, newer history wins):\n").appendLine(m.content.orEmpty())
                    else append("USER:\n").appendLine(m.content.orEmpty())
                Role.ASSISTANT -> {
                    append("ASSISTANT:\n")
                    m.content?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                    m.toolCalls?.forEach { appendLine("[tool call ${it.function.name}] ${it.function.arguments.take(MAX_ARG)}") }
                }
                Role.TOOL -> append("TOOL RESULT (${m.name ?: m.toolCallId}):\n").appendLine(m.content.orEmpty().take(MAX_RESULT))
                else -> append(m.role.uppercase()).append(":\n").appendLine(m.content.orEmpty())
            }
            appendLine()
        }
    }.trim()

    companion object {
        const val DEFAULT_KEEP = 6
        const val PRUNE_PROTECT = 120_000
        private const val PRUNE_MIN = 300
        const val PRUNED = "[old tool output cleared to save context:"
        private const val MAX_ARG = 1500
        private const val MAX_RESULT = 2500
    }
}
