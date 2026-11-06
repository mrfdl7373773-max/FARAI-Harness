package dev.harness.core.files

import dev.harness.net.ChatMessage
import dev.harness.net.Role
import dev.harness.tools.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A file or folder the agent made during one turn, shown as a tappable card in the chat. */
data class ProducedFile(val path: String, val display: String, val isDirectory: Boolean, val size: Long)

/**
 * Finds what a turn produced: every path given to `write` or `edit`, plus paths the agent named
 * in its replies, finish summary or subagent results (in backticks or with a known extension)
 * that exist on disk. The second part catches zips, folders and files made through `bash`.
 */
object ProducedFiles {
    private const val MAX = 8
    private val json = Json { ignoreUnknownKeys = true }
    private val BACKTICK = Regex("`([^`\\n]{1,300})`")
    private val EXTENSIONS = listOf(
        "zip", "tar", "gz", "tgz", "py", "md", "html", "htm", "css", "js", "jsx", "ts", "tsx", "json", "yaml", "yml",
        "toml", "txt", "csv", "kt", "kts", "java", "c", "h", "cpp", "rs", "go", "rb", "php", "sh", "sql", "xml", "svg",
        "png", "jpg", "jpeg", "gif", "webp", "pdf", "apk", "ipynb", "lua", "dart", "swift", "vue", "svelte", "ini", "cfg", "log",
    )
    private val BY_EXTENSION = Regex(
        "(?<![\\w/.-])((?:~/|/|\\./)?[\\w.@+-]+(?:/[\\w.@+-]+)*\\.(?:" + EXTENSIONS.joinToString("|") + "))(?![\\w])",
        RegexOption.IGNORE_CASE,
    )

    /** Files for each turn, keyed by the index of the turn's last message. */
    fun byTurn(messages: List<ChatMessage>, workspace: Workspace): Map<Int, List<ProducedFile>> {
        val out = linkedMapOf<Int, List<ProducedFile>>()
        var start = 0
        for (i in messages.indices) {
            val endsTurn = i == messages.lastIndex || messages[i + 1].role == Role.USER
            if (!endsTurn) continue
            val found = collect(messages.subList(start, i + 1), workspace)
            if (found.isNotEmpty()) out[i] = found
            start = i + 1
        }
        return out
    }

    fun collect(turn: List<ChatMessage>, workspace: Workspace): List<ProducedFile> {
        val written = mutableListOf<String>()
        val mentioned = mutableListOf<String>()
        val results = turn.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
        for (m in turn) {
            if (m.role != Role.ASSISTANT) continue
            m.content?.let { mentioned += candidates(it) }
            m.toolCalls?.forEach { tc ->
                val r = results[tc.id]?.content.orEmpty()
                val ok = !r.startsWith("ERROR")
                when (tc.function.name) {
                    "write", "edit", "multiedit" -> if (ok) argPath(tc.function.arguments)?.let { written += it }
                    "patch" -> if (ok) Regex("(?m)^\\*\\*\\* (?:Add|Update) File:\\s*(.+)$").findAll(
                        dev.harness.core.ToolRepair.parseArguments(tc.function.arguments)?.get("patch")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                    ).forEach { written += it.groupValues[1].trim() }
                    "finish" -> mentioned += candidates(r + "\n" + tc.function.arguments)
                    "task" -> if (ok) mentioned += candidates(r)
                }
            }
        }
        val seen = LinkedHashMap<String, ProducedFile>()
        for (raw in written + mentioned) {
            if (seen.size >= MAX) break
            val f = resolve(raw, workspace) ?: continue
            if (f == workspace.root) continue
            val key = f.absolutePath
            if (key in seen) continue
            seen[key] = ProducedFile(key, display(f, workspace), f.isDirectory, if (f.isFile) f.length() else 0L)
        }
        // With many entries, files inside a listed folder are reached through that folder.
        val list = seen.values.toList()
        return if (list.size <= 4) list else list.filterNot { p -> list.any { d -> d.isDirectory && p.path.startsWith(d.path + "/") } }
    }

    private fun argPath(arguments: String): String? = runCatching {
        json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()

    internal fun candidates(text: String): List<String> {
        val out = mutableListOf<String>()
        BACKTICK.findAll(text).forEach { m ->
            val t = m.groupValues[1].trim().trimEnd('.', ',', ':', ';')
            if (!t.contains(' ') && (t.contains('/') || t.contains('.'))) out += t
        }
        BY_EXTENSION.findAll(text).forEach { out += it.groupValues[1] }
        return out
    }

    private fun resolve(raw: String, workspace: Workspace): File? {
        val p = raw.removePrefix("~/").removePrefix("file://").trim()
        if (p.isEmpty() || p.contains("://")) return null
        return runCatching { workspace.resolve(p) }.getOrNull()?.takeIf { it.exists() }
    }

    private fun display(f: File, workspace: Workspace): String =
        runCatching { workspace.relative(f) }.getOrDefault(f.name).let { if (f.isDirectory) it.trimEnd('/') + "/" else it }
}
