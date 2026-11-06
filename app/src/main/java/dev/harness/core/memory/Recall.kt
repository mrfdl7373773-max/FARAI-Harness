package dev.harness.core.memory

import dev.harness.core.ChatText
import dev.harness.net.ChatMessage
import dev.harness.net.HarnessJson
import dev.harness.net.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recall memory: everything ever said, searchable.
 * FARAI reads the saved sessions and, because compaction replaces old messages with a summary,
 * first archives what compaction removes (`.recall/<session>.jsonl`), so nothing is ever lost.
 *
 * Each user message carries the date reminder the engine adds, so results show when it was said.
 */
class RecallStore(
    private val dir: File,
    /** Saved sessions: (id, title, updatedAt, messages). */
    private val sessions: suspend () -> List<RecallSession>,
) {
    data class RecallSession(val id: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>)

    @Serializable
    data class Archived(val session: String, val title: String = "", val time: Long, val messages: List<ChatMessage>)

    data class Msg(val session: String, val title: String, val index: Int, val role: String, val text: String, val date: String?, val archived: Boolean)

    data class Hit(val unit: Msg, val score: Double)

    /** Saves messages that compaction is about to drop. */
    fun archive(sessionId: String, title: String, removed: List<ChatMessage>) {
        if (removed.isEmpty()) return
        dir.mkdirs()
        val f = File(dir, safe(sessionId) + ".jsonl")
        f.appendText(HarnessJson.encodeToString(Archived(sessionId, title, System.currentTimeMillis(), removed)) + "\n")
    }

    fun deleteArchive(sessionId: String) { File(dir, safe(sessionId) + ".jsonl").delete() }

    private fun archived(): List<Archived> =
        (dir.listFiles() ?: emptyArray()).filter { it.extension == "jsonl" }.flatMap { f ->
            f.readLines().mapNotNull { runCatching { HarnessJson.decodeFromString<Archived>(it) }.getOrNull() }
        }

    /** All searchable units: one per user/assistant message (tool traffic summarised), oldest first. */
    suspend fun units(includeTools: Boolean = false): List<Msg> {
        val out = ArrayList<Msg>()
        val titles = HashMap<String, String>()
        val live = runCatching { sessions() }.getOrDefault(emptyList())
        live.forEach { titles[it.id] = it.title }
        archived().forEach { a ->
            var date: String? = null
            a.messages.forEachIndexed { i, m -> toUnit(a.session, titles[a.session] ?: a.title, -1 - i, m, includeTools, date)?.let { u -> date = u.date; out += u.copy(archived = true) } }
        }
        live.forEach { s ->
            val date: String = fmt(s.updatedAt)
            var lastDate: String? = null
            s.messages.forEachIndexed { i, m ->
                toUnit(s.id, s.title, i, m, includeTools, lastDate)?.let { u -> lastDate = u.date; out += u.copy(date = u.date ?: date) }
            }
        }
        return out
    }

    private fun toUnit(session: String, title: String, index: Int, m: ChatMessage, includeTools: Boolean, carryDate: String?): Msg? {
        val raw = m.content.orEmpty()
        val date = DATE_LINE.find(raw)?.groupValues?.get(1)?.trim() ?: carryDate
        return when (m.role) {
            Role.USER -> {
                if (ChatText.isCheckpoint(raw)) return null
                val text = ChatText.stripReminders(raw).trim()
                if (text.isEmpty()) null else Msg(session, title, index, "user", text, date, false)
            }
            Role.ASSISTANT -> {
                val calls = m.toolCalls.orEmpty().joinToString(" ") { "[${it.function.name}] ${it.function.arguments.take(160)}" }
                val text = listOf(raw.trim(), calls).filter { it.isNotBlank() }.joinToString("\n")
                if (text.isEmpty()) null else Msg(session, title, index, "assistant", text, date, false)
            }
            Role.TOOL -> if (includeTools && raw.isNotBlank()) Msg(session, title, index, "tool:${m.name ?: "?"}", raw.take(2000), date, false) else null
            else -> null
        }
    }

    suspend fun search(query: String, limit: Int = 8, session: String? = null, role: String? = null, after: String? = null, before: String? = null, excludeSession: String? = null): List<Hit> {
        val all = units(includeTools = role?.startsWith("tool") == true).filter { u ->
            (session == null || u.session == session || u.session.endsWith(session)) &&
                (excludeSession == null || u.session != excludeSession || u.archived) &&
                (role == null || u.role.startsWith(role)) &&
                inRange(u.date, after, before)
        }
        if (query.isBlank()) return all.takeLast(limit).reversed().map { Hit(it, 0.0) }
        val ranked = Bm25.rank(query, all.map { "${it.text} ${it.title}" })
        return ranked.take(limit).map { (i, s) -> Hit(all[i], s) }
    }

    /** Messages around one hit, for context ("needle + expand"). */
    suspend fun around(session: String, index: Int, before: Int = 3, after: Int = 3): List<Msg> {
        val list = units().filter { it.session == session || it.session.endsWith(session) }
        val at = list.indexOfFirst { it.index == index }.takeIf { it >= 0 } ?: return emptyList()
        return list.subList((at - before).coerceAtLeast(0), (at + after + 1).coerceAtMost(list.size))
    }

    private fun inRange(date: String?, after: String?, before: String?): Boolean {
        if (after == null && before == null) return true
        val d = date?.let(::parseDay) ?: return false
        after?.let(::parseDay)?.let { if (d < it) return false }
        before?.let(::parseDay)?.let { if (d > it) return false }
        return true
    }

    companion object {
        /** "Current date and time: Friday, 6 November 2026, 14:36 (Asia/Jakarta, UTC+07:00)" */
        private val DATE_LINE = Regex("Current date and time: ([^()\\n]+?)(?: \\(|\\.|\\n)")
        private val DAY_FORMATS = listOf("yyyy-MM-dd", "EEEE, d MMMM yyyy, HH:mm", "EEEE, d MMMM yyyy", "d MMMM yyyy")

        fun fmt(ms: Long): String = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(Date(ms))

        /** Day number (yyyyMMdd) of a date string in one of the formats above, or null. */
        fun parseDay(s: String): Int? {
            val t = s.trim().trimEnd(',', '.')
            for (f in DAY_FORMATS) {
                val d = runCatching { SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(t) }.getOrNull() ?: continue
                return SimpleDateFormat("yyyyMMdd", Locale.US).format(d).toInt()
            }
            // "Friday, 6 November 2026, 14:36" with extra text after it.
            Regex("(\\d{1,2} [A-Za-z]+ \\d{4})").find(t)?.let { m ->
                val d = runCatching { SimpleDateFormat("d MMMM yyyy", Locale.US).parse(m.value) }.getOrNull()
                if (d != null) return SimpleDateFormat("yyyyMMdd", Locale.US).format(d).toInt()
            }
            return null
        }

        private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
