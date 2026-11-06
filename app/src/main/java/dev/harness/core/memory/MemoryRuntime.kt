package dev.harness.core.memory

import dev.harness.core.ChatText
import dev.harness.core.Clock
import dev.harness.core.PromptRepository
import dev.harness.core.SessionState
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatClient
import dev.harness.net.ChatMessage
import dev.harness.net.ChatRequest
import dev.harness.net.Role
import dev.harness.net.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything memory does around the agent loop:
 *
 * 1. **Compile** core memory into the system prompt once per user message (so a run keeps one
 *    stable prompt, which providers can cache; edits show up from the next message
 *    recompile).
 * 2. **Run** the `memory` and `recall` tools.
 * 3. **Nudge**: when a message sounds like a lasting preference, correction or fact ("always…",
 *    "jangan…", "aku suka…", "kenapa kamu lupa…"), a short reminder asks the agent to save it.
 * 4. **Reflect** (sleep-time agent): in the background, every few user messages and before
 *    compaction drops old messages, a separate model call reads what happened since the last
 *    reflection and updates memory with only the `memory` and `recall` tools. It never blocks
 *    the chat and its changes are in history like any other.
 * 5. **Archive** messages compaction removes, so recall can still find them.
 */
class MemoryRuntime(
    val store: MemoryStore,
    val recall: RecallStore,
    private val prompts: PromptRepository,
    private val client: ChatClient,
    private val settings: suspend () -> AppSettings,
    private val scope: CoroutineScope,
    private val transcript: (List<ChatMessage>) -> String,
    /** Tool schemas for the reflection agent (memory + recall), with their prompt-file descriptions. */
    private val reflectionTools: () -> List<ToolSchema> = { emptyList() },
    private val stateFile: File = File(store.root, ".history/reflect.json"),
) {
    data class Report(val sessionId: String, val changes: Int, val summary: String, val failed: Boolean = false)

    private val _reports = MutableSharedFlow<Report>(extraBufferCapacity = 8)
    /** One per finished background reflection (the chat shows a small note when it changed something). */
    val reports: SharedFlow<Report> = _reports.asSharedFlow()
    private val _reflecting = MutableStateFlow<Set<String>>(emptySet())
    val reflecting: StateFlow<Set<String>> = _reflecting.asStateFlow()

    private val reflectLock = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, Int>()

    init { loadProgress() }

    suspend fun enabled(): Boolean = runCatching { settings().agent.memory }.getOrDefault(true)

    // ---- 1. Prompt ----------------------------------------------------------------------------

    /** The memory section of the system prompt, or null when memory is off. */
    suspend fun compile(): String? {
        if (!enabled()) return null
        store.ensureSeeded()
        val guide = prompts.readOrNull(GUIDE)?.trim().orEmpty()
        return buildString {
            if (guide.isNotEmpty()) appendLine(guide).appendLine()
            append(renderScope(MemoryStore.Scope.AGENT, "agent", "Your own memory. It travels with you across every workspace and session."))
            store.projectRoot?.takeIf { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }?.let {
                appendLine().appendLine()
                append(renderScope(MemoryStore.Scope.PROJECT, "project", "Memory of the current workspace (addressed as project/…)."))
            }
        }.trim()
    }

    private fun renderScope(scope: MemoryStore.Scope, label: String, intro: String): String = buildString {
        val blocks = store.coreBlocks(scope)
        val total = store.coreTotal(scope)
        appendLine("<memory scope=\"$label\" core_chars=\"$total/${store.limits.coreTotal}\">")
        appendLine(intro)
        blocks.forEach { b ->
            val tag = b.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
            append("<$tag path=\"${b.path}\" chars=\"${b.chars}/${b.limit}\"")
            if (b.readOnly) append(" read_only=\"true\"")
            appendLine(">")
            if (b.description.isNotBlank()) appendLine("<description>${b.description}</description>")
            appendLine(b.body.ifBlank { "(empty)" })
            appendLine("</$tag>")
        }
        store.rootIndex(scope)?.let { idx ->
            appendLine("<index path=\"${if (scope == MemoryStore.Scope.PROJECT) "project/" else ""}MEMORY.md\">")
            appendLine(idx)
            appendLine("</index>")
        }
        val dirs = store.deferredDirs(scope)
        if (dirs.isNotEmpty()) {
            appendLine("<deferred>")
            dirs.forEach { d -> appendLine("- ${d.path} — ${d.summary ?: "(no summary)"} (${d.files} files; open with memory view)") }
            appendLine("</deferred>")
        }
        append("</memory>")
    }

    /** Read-only core memory for subagents: who the user is and the rules, without the tools guide. */
    suspend fun compileForSubagent(): String? {
        if (!enabled()) return null
        val blocks = store.coreBlocks(MemoryStore.Scope.AGENT) + store.coreBlocks(MemoryStore.Scope.PROJECT)
        if (blocks.isEmpty()) return null
        return buildString {
            appendLine("# What the main agent remembers (read-only)")
            blocks.forEach { b -> appendLine("<${b.name}>"); appendLine(b.body.take(4000)); appendLine("</${b.name}>") }
        }.trim()
    }

    // ---- 2. Tools -----------------------------------------------------------------------------

    /** Runs a `memory` tool call. Returns the text for the model; throws [MemoryException] on misuse. */
    fun runMemoryTool(args: JsonObject, source: String?): String {
        store.ensureSeeded()
        val action = (args.s("action") ?: args.s("command") ?: "view").lowercase()
        val path = args.s("path") ?: args.s("file")
        val reason = args.s("reason")
        fun need(k: String, vararg alt: String): String = (sequenceOf(k, *alt).mapNotNull { args.s(it) }.firstOrNull())
            ?: throw MemoryException("'$action' needs '$k'.")
        return when (action) {
            "view", "read", "list", "ls", "cat" ->
                if (path.isNullOrBlank() || store.resolve(path).file.isDirectory) store.read(path ?: "")
                else store.readNumbered(path, args.i("offset") ?: 1, args.i("limit") ?: 400)
            "create", "write" -> store.create(need("path", "file"), need("content", "text", "file_text"), args.s("description"), reason, source, overwrite = args.b("overwrite") == true)
            "str_replace", "replace", "edit" -> store.replace(need("path", "file"), need("old_text", "old_str", "old"), args.s("new_text") ?: args.s("new_str") ?: args.s("new") ?: "", args.b("replace_all") == true, reason, source)
            "append", "add" -> store.append(need("path", "file"), need("text", "content", "new_text"), reason, source)
            "insert" -> store.insert(need("path", "file"), args.i("line") ?: args.i("insert_line") ?: 0, need("text", "content", "new_text"), reason, source)
            "rewrite", "rethink" -> store.rewrite(need("path", "file"), need("content", "text", "new_memory"), args.s("description"), reason, source)
            "delete", "remove", "rm" -> store.delete(need("path", "file"), reason, source)
            "move", "rename", "mv" -> store.move(need("path", "from", "old_path"), need("new_path", "to"), reason, source)
            "search", "grep", "find" -> {
                val q = need("query", "text")
                val hits = store.search(q, (args.i("limit") ?: 10).coerceIn(1, 30))
                if (hits.isEmpty()) "No memory matches \"$q\". Try other words, or `recall` to search past conversations."
                else hits.joinToString("\n") { "${it.path}:${it.line}: ${it.snippet}" }
            }
            "history", "log" -> {
                val list = store.history(path, (args.i("limit") ?: 15).coerceIn(1, 50))
                if (list.isEmpty()) "No memory changes yet." else list.joinToString("\n") { c ->
                    "${c.id} · ${RecallStore.fmt(c.time)} · ${c.op} ${c.path}${c.reason?.let { r -> " — $r" } ?: ""}"
                }
            }
            "revert", "undo" -> store.revert(need("id", "change_id"), source)
            else -> throw MemoryException("Unknown action '$action'. Use view, create, str_replace, append, insert, rewrite, delete, move, search, history or revert.")
        }
    }

    /** Runs a `recall` tool call over past conversations. */
    suspend fun runRecallTool(args: JsonObject, currentSession: String?): String {
        val around = args.i("around")
        val session = args.s("session")
        if (around != null && session != null) {
            val list = recall.around(session, around, args.i("before_count") ?: 4, args.i("after_count") ?: 4)
            if (list.isEmpty()) return "Nothing found at session $session message $around."
            return list.joinToString("\n\n") { u -> "[#${u.index} ${u.role}${u.date?.let { d -> " · $d" } ?: ""}]\n${u.text.take(1500)}" }
        }
        val query = args.s("query") ?: ""
        val hits = recall.search(
            query = query,
            limit = (args.i("limit") ?: 8).coerceIn(1, 25),
            session = session,
            role = args.s("role"),
            after = args.s("after") ?: args.s("start_date"),
            before = args.s("before") ?: args.s("end_date"),
            excludeSession = if (args.b("include_current") == true) null else currentSession,
        )
        if (hits.isEmpty()) return "No past messages match \"$query\"." + (if (args.s("after") != null || args.s("before") != null) " (Dates filter by the date each message was sent.)" else "")
        return buildString {
            appendLine("${hits.size} match(es). Expand one with session + around.")
            hits.forEach { h ->
                val u = h.unit
                appendLine()
                appendLine("session=${u.session} around=${u.index} · \"${u.title.take(60)}\" · ${u.role}${u.date?.let { " · $it" } ?: ""}${if (u.archived) " · (compacted)" else ""}")
                appendLine(snippet(u.text, query))
            }
        }.trim()
    }

    private fun snippet(text: String, query: String, width: Int = 420): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= width) return t
        val words = Bm25.tokens(query)
        val at = words.map { w -> t.lowercase().indexOf(w) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (at - width / 3).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(t.length)
        return (if (start > 0) "…" else "") + t.substring(start, end) + (if (end < t.length) "…" else "")
    }

    // ---- 3. Nudges ------------------------------------------------------------------------------

    /** A reminder to add to a user message that sounds like something worth remembering, or null. */
    suspend fun nudgeFor(userInput: String): String? {
        if (!enabled()) return null
        val t = userInput.lowercase()
        if (t.length < 6 || t.startsWith("/")) return null
        val kind = when {
            FORGET.containsMatchIn(t) -> "forget"
            CORRECTION.containsMatchIn(t) -> "correction"
            PREFERENCE.containsMatchIn(t) -> "preference"
            FACT.containsMatchIn(t) -> "fact"
            else -> return null
        }
        return when (kind) {
            "forget" -> "The user may be asking you to forget something. If so, find every copy with memory search, remove or rewrite it, and confirm in one line."
            "correction" -> "This message may correct you or show frustration. Besides fixing the task, decide whether it reveals a lasting rule; if it does, sharpen the matching line in memory (one home per rule) so it doesn't happen again."
            else -> "This message may contain a lasting preference or fact about the user or project. If it will matter in future conversations, save it with the memory tool (update the existing line instead of adding a duplicate). Don't save one-off task details."
        }
    }

    // ---- 4/5. Reflection and archive -------------------------------------------------------------

    /** Called after every run. Starts a background reflection when enough has happened. */
    suspend fun afterRun(state: SessionState, title: String) {
        val s = runCatching { settings() }.getOrNull() ?: return
        if (!s.agent.memory || s.agent.reflectEvery <= 0) return
        if (state.id.contains("/sub-")) return
        val users = state.userMessageCount()
        val last = progress[state.id] ?: 0
        if (users - last >= s.agent.reflectEvery) launchReflection(state.id, title, state.messages.toList(), fromUser = last, reason = "every ${s.agent.reflectEvery} messages")
    }

    /** Called right before compaction replaces [removed] with a summary: archive them and reflect on them. */
    suspend fun beforeCompaction(state: SessionState, title: String, removed: List<ChatMessage>) {
        if (removed.isEmpty() || state.id.contains("/sub-")) return
        runCatching { recall.archive(state.id, title, removed) }
        val s = runCatching { settings() }.getOrNull() ?: return
        if (!s.agent.memory || s.agent.reflectEvery < 0) return
        launchReflection(state.id, title, removed, fromUser = 0, reason = "before compaction", markUpTo = null)
    }

    /** /memory reflect: reflects on the whole session now (or what's new since the last time). */
    fun reflectNow(state: SessionState, title: String, all: Boolean = false) {
        val last = if (all) 0 else progress[state.id] ?: 0
        launchReflection(state.id, title, state.messages.toList(), fromUser = last, reason = "requested", force = true)
    }

    private fun launchReflection(sessionId: String, title: String, messages: List<ChatMessage>, fromUser: Int, reason: String, markUpTo: Int? = -1, force: Boolean = false) {
        if (!force && jobs[sessionId]?.isActive == true) return
        val users = messages.count { it.role == Role.USER && !ChatText.isSynthetic(it.content) }
        jobs[sessionId] = scope.launch {
            _reflecting.value = _reflecting.value + sessionId
            try {
                val slice = sliceFromUser(messages, fromUser)
                if (slice.none { it.role == Role.USER && !ChatText.isSynthetic(it.content) }) return@launch
                val r = reflectLock.withLock { reflect(sessionId, title, slice, reason) }
                if (markUpTo != null) { progress[sessionId] = if (markUpTo < 0) users else markUpTo; saveProgress() }
                _reports.tryEmit(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reports.tryEmit(Report(sessionId, 0, "Memory reflection failed: ${e.message}", failed = true))
            } finally {
                _reflecting.value = _reflecting.value - sessionId
            }
        }
    }

    private fun sliceFromUser(messages: List<ChatMessage>, fromUser: Int): List<ChatMessage> {
        if (fromUser <= 0) return messages
        var seen = 0
        messages.forEachIndexed { i, m ->
            if (m.role == Role.USER && !ChatText.isSynthetic(m.content)) {
                if (seen == fromUser) return messages.drop(i)
                seen++
            }
        }
        return emptyList()
    }

    /** The reflection agent: a small tool loop with only memory and recall. */
    suspend fun reflect(sessionId: String, title: String, slice: List<ChatMessage>, reason: String): Report {
        val s = settings()
        store.ensureSeeded()
        val prompt = prompts.readOrNull(REFLECTION)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REFLECTION
        val text = transcript(slice).let { if (it.length > MAX_TRANSCRIPT) "…(earlier part cut)…\n" + it.takeLast(MAX_TRANSCRIPT) else it }
        val memoryNow = compile().orEmpty()
        val system = listOf(prompt, "<environment>\ndate: ${Clock.nowLine()}\n</environment>", "# The primary agent's memory right now\n$memoryNow").joinToString("\n\n")
        val messages = mutableListOf(
            ChatMessage(Role.SYSTEM, system),
            ChatMessage(Role.USER, "Session \"$title\" ($sessionId). Reflection trigger: $reason.\n\n<transcript>\n$text\n</transcript>\n\nUpdate memory now if anything is worth keeping, then reply with a 1-3 line report (or \"No changes.\")."),
        )
        val before = store.history(limit = MemoryStore.MAX_HISTORY).size
        val schemas = reflectionTools()
        var final = ""
        for (step in 1..MAX_REFLECTION_STEPS) {
            val result = client.chat(ChatRequest(
                model = s.agent.reflectionModel.ifBlank { s.provider.model },
                messages = messages,
                tools = schemas.ifEmpty { null },
                temperature = 0.2f,
                maxTokens = s.provider.maxTokens.takeIf { it > 0 },
                stream = false,
            ))
            messages += ChatMessage(Role.ASSISTANT, result.content.ifEmpty { null }, result.toolCalls.ifEmpty { null })
            if (result.toolCalls.isEmpty()) { final = result.content; break }
            for (call in result.toolCalls) {
                val args = runCatching { dev.harness.net.HarnessJson.parseToJsonElement(call.function.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()
                val out = if (args == null) "ERROR: arguments are not valid JSON" else try {
                    when (call.function.name) {
                        "memory" -> runMemoryTool(args, "reflection:$sessionId")
                        "recall" -> runRecallTool(args, null)
                        else -> "ERROR: only the memory and recall tools are available here."
                    }
                } catch (e: MemoryException) { "ERROR: ${e.message}" }
                messages += ChatMessage(Role.TOOL, out.take(12_000), toolCallId = call.id, name = call.function.name)
            }
        }
        val after = store.history(limit = MemoryStore.MAX_HISTORY).size
        val changes = (after - before).coerceAtLeast(0)
        return Report(sessionId, changes, final.replace(Regex("(?s)<think>.*?</think>"), "").trim().ifBlank { if (changes == 0) "No changes." else "$changes change(s)." })
    }

    fun forgetSession(sessionId: String) {
        progress.remove(sessionId); saveProgress()
        recall.deleteArchive(sessionId)
    }

    private fun loadProgress() {
        runCatching {
            if (!stateFile.isFile) return
            dev.harness.net.HarnessJson.parseToJsonElement(stateFile.readText()).jsonObject.forEach { (k, v) ->
                (v as? JsonPrimitive)?.intOrNull?.let { progress[k] = it }
            }
        }
    }

    private fun saveProgress() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(kotlinx.serialization.json.JsonObject(progress.mapValues { JsonPrimitive(it.value) }).toString())
        }
    }

    companion object {
        const val GUIDE = "memory.md"
        const val REFLECTION = "memory/reflection.md"
        const val INIT = "templates/memory-init.md"
        private const val MAX_TRANSCRIPT = 60_000
        private const val MAX_REFLECTION_STEPS = 12

        private val FORGET = Regex("\\b(forget|lupakan|lupain|hapus (dari )?(memori|ingatan)|jangan (diingat|ingat))")
        private val CORRECTION = Regex("(kenapa (kamu|lu|lo|kau)|why did you|i (already )?told you|udah (aku |ku)?bilang|sudah (aku |saya )?bilang|kok (malah|lupa)|you forgot|kamu lupa|salah lagi|again\\?|bukan gitu|bukan itu|that's wrong|stop doing)")
        private val PREFERENCE = Regex("\\b(always|never|from now on|i prefer|i like|i hate|i don'?t like|remember|ingat(lah|kan)?|selalu|jangan pernah|mulai (sekarang|skrg)|aku (lebih )?suka|aku gak suka|aku nggak suka|aku ga suka|aku benci|saya (lebih )?suka|tolong (selalu|jangan)|biasanya aku|pakai(lah)? (bahasa|gaya)|panggil aku)")
        private val FACT = Regex("\\b(my name is|i'?m a|i work|namaku|nama aku|nama saya|aku kerja|saya kerja|aku tinggal|saya tinggal|aku lagi (bikin|buat|ngerjain)|project ?ku|proyekku|ulang tahun)")

        val DEFAULT_REFLECTION = """
            You maintain the long-term memory of FARAI, a coding agent. Read the transcript and update memory with the memory tool.
            Keep only what will help future conversations: mistakes and corrections, preferences, durable facts about the user and projects, and contradictions with what memory says.
            Integrate instead of duplicating: read the file first, sharpen existing lines, delete what's stale. Skip one-off task details. Convert relative dates to absolute ones.
            Always pass a short `reason` naming the evidence. Reply with a 1-3 line report, or "No changes." if nothing is worth keeping.
        """.trimIndent()
    }
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
