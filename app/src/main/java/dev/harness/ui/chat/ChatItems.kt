package dev.harness.ui.chat

/** What the chat list renders. Built from the session's messages plus live engine events. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem
    data class Assistant(override val key: String, val text: String) : ChatItem
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val isError: Boolean = false,
        val running: Boolean = true,
        val children: List<Tool> = emptyList(),
        val liveText: String = "",
    ) : ChatItem
    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : ChatItem
    data class Finished(override val key: String, val summary: String) : ChatItem
    /** Files and folders the agent made in a turn; each one opens in the file viewer. */
    data class Files(override val key: String, val files: List<dev.harness.core.files.ProducedFile>) : ChatItem
    data class Compacted(override val key: String, val summary: String, val removed: Int) : ChatItem
}
