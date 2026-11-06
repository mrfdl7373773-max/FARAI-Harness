package dev.harness.core

import dev.harness.data.session.SessionDao
import dev.harness.data.session.SessionEntity
import dev.harness.net.ChatMessage
import dev.harness.net.HarnessJson
import dev.harness.net.Role
import dev.harness.tools.TodoStore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object SessionStatus {
    const val ACTIVE = "ACTIVE"
    const val FINISHED = "FINISHED"
    const val CLOSED = "CLOSED"
}

/** Persists [SessionState] to Room and offers session-level operations. */
class SessionManager(private val dao: SessionDao) {

    val sessions: Flow<List<SessionEntity>> = dao.observeAll()

    /**
     * Creates a session. When [reuseEmpty] is on (the default for "+ New session") and an untouched
     * "New session" already exists, that one is brought back to the top instead of adding another
     * empty one; any extra empty duplicates left over from older versions are removed.
     */
    suspend fun create(
        model: String,
        agent: String = dev.harness.core.agents.AgentStore.DEFAULT,
        title: String = DEFAULT_TITLE,
        reuseEmpty: Boolean = true,
    ): SessionEntity {
        val now = System.currentTimeMillis()
        if (reuseEmpty && title == DEFAULT_TITLE) {
            val empties = dao.all().filter(::isUntouched)
            empties.firstOrNull()?.let { keep ->
                empties.drop(1).forEach { dao.delete(it.id) }
                val revived = keep.copy(updatedAt = now, model = model, mode = agent)
                dao.upsert(revived)
                return revived
            }
        }
        val e = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            mode = agent,
            model = model,
            messagesJson = "[]",
            todoJson = "[]",
            status = SessionStatus.ACTIVE,
        )
        dao.upsert(e)
        return e
    }

    suspend fun get(id: String): SessionEntity? = dao.get(id)

    suspend fun load(id: String): SessionState? {
        val e = dao.get(id) ?: return null
        return toState(e)
    }

    fun toState(e: SessionEntity): SessionState = SessionState(
        id = e.id,
        messages = decodeMessages(e.messagesJson),
        todos = TodoStore(TodoStore.fromJson(e.todoJson)),
        agent = dev.harness.core.agents.AgentStore.legacyName(e.mode),
        disabledTools = e.disabledTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet(),
    ).also { it.finished = e.status == SessionStatus.FINISHED }

    suspend fun save(state: SessionState, model: String, titleOverride: String? = null, status: String? = null): SessionEntity {
        val existing = dao.get(state.id)
        val now = System.currentTimeMillis()
        val title = titleOverride ?: existing?.title?.takeIf { it != DEFAULT_TITLE } ?: autoTitle(state.messages) ?: DEFAULT_TITLE
        val e = SessionEntity(
            id = state.id,
            title = title,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            mode = state.agent,
            model = model,
            messagesJson = HarnessJson.encodeToString(state.messages.toList()),
            todoJson = state.todos.toJson(),
            status = status ?: if (state.finished) SessionStatus.FINISHED else (existing?.status ?: SessionStatus.ACTIVE),
            tags = existing?.tags ?: "",
            messageCount = state.messages.count { (it.role == Role.USER && !ChatText.isSynthetic(it.content)) || it.role == Role.ASSISTANT },
            parentId = existing?.parentId,
            disabledTools = state.disabledTools.sorted().joinToString(","),
        )
        dao.upsert(e)
        return e
    }

    suspend fun rename(id: String, title: String) = dao.rename(id, title.trim().ifEmpty { DEFAULT_TITLE }, System.currentTimeMillis())

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun setStatus(id: String, status: String) {
        dao.get(id)?.let { dao.upsert(it.copy(status = status, updatedAt = System.currentTimeMillis())) }
    }

    /** Copies a session (optionally only the first [upToMessages] messages) into a new one. */
    suspend fun fork(id: String, upToMessages: Int? = null, newTitle: String? = null): SessionEntity? {
        val e = dao.get(id) ?: return null
        val msgs = decodeMessages(e.messagesJson).let { if (upToMessages != null) it.take(upToMessages) else it }
        val now = System.currentTimeMillis()
        val copy = e.copy(
            id = UUID.randomUUID().toString(),
            title = newTitle ?: "${e.title} (fork)",
            createdAt = now,
            updatedAt = now,
            messagesJson = HarnessJson.encodeToString(msgs),
            status = SessionStatus.ACTIVE,
            messageCount = msgs.count { it.role == Role.USER || it.role == Role.ASSISTANT },
            parentId = e.id,
        )
        dao.upsert(copy)
        return copy
    }

    suspend fun setTags(id: String, tags: List<String>) {
        dao.get(id)?.let { dao.upsert(it.copy(tags = tags.map { t -> t.trim().lowercase() }.filter { t -> t.isNotEmpty() }.distinct().joinToString(","), updatedAt = System.currentTimeMillis())) }
    }

    /** Pins or unpins a session (the "pinned" tag) without moving it in the recent order. */
    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.get(id)?.let { e ->
            val t = e.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != PINNED_TAG } + if (pinned) listOf(PINNED_TAG) else emptyList()
            dao.upsert(e.copy(tags = t.joinToString(",")))
        }
    }

    suspend fun search(query: String): List<SessionEntity> = dao.search(query.trim())
    suspend fun byTag(tag: String): List<SessionEntity> = dao.byTag(tag.trim().lowercase())
    suspend fun all(): List<SessionEntity> = dao.all()

    fun decodeMessages(json: String): List<ChatMessage> =
        runCatching { HarnessJson.decodeFromString<List<ChatMessage>>(json) }.getOrDefault(emptyList())

    /** Plain-text transcript for sharing. */
    fun transcript(e: SessionEntity, messages: List<ChatMessage> = decodeMessages(e.messagesJson)): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        appendLine("# ${e.title}")
        appendLine("Model: ${e.model} · Agent: ${dev.harness.core.agents.AgentStore.legacyName(e.mode)} · Created: ${fmt.format(Date(e.createdAt))}")
        appendLine()
        for (m in messages) {
            when (m.role) {
                Role.USER -> {
                    if (ChatText.isSynthetic(m.content)) { appendLine("## Harness"); appendLine(m.content.orEmpty().take(2000)) }
                    else { appendLine("## You"); appendLine(ChatText.stripReminders(m.content.orEmpty())) }
                }
                Role.ASSISTANT -> {
                    appendLine("## Agent")
                    m.content?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                    m.toolCalls?.forEach { appendLine("→ ${it.function.name} ${it.function.arguments}") }
                }
                Role.TOOL -> { appendLine("### Result: ${m.name ?: "tool"}"); appendLine("```"); appendLine(m.content.orEmpty().take(4000)); appendLine("```") }
                else -> Unit
            }
            appendLine()
        }
    }.trimEnd()

    fun autoTitle(messages: List<ChatMessage>): String? =
        messages.firstOrNull { it.role == Role.USER && !ChatText.isSynthetic(it.content) }?.content?.let(ChatText::stripReminders)?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.let { if (it.length > 48) it.take(47).trimEnd() + "…" else it }

    companion object {
        const val DEFAULT_TITLE = "New session"

        /** A blank "New session": no messages, never renamed, tagged or forked. */
        const val PINNED_TAG = "pinned"

        fun isPinned(e: SessionEntity): Boolean = e.tags.split(',').any { it.trim() == PINNED_TAG }

        fun isUntouched(e: SessionEntity): Boolean =
            e.title == DEFAULT_TITLE && e.messageCount == 0 && e.messagesJson.trim().let { it.isEmpty() || it == "[]" } &&
                e.parentId == null && e.tags.isBlank() && e.status == SessionStatus.ACTIVE
    }
}
