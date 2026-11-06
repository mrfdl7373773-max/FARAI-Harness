package dev.harness.core

import dev.harness.net.HarnessJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Models carry habits over from other harnesses: they call `shell` instead of `bash`, send
 * `"limit": "20"`, `filePath` instead of `path`, or double-encode the arguments. Instead of failing
 * the call, fix what is unambiguous and tell the model the right name for next time.
 */
object ToolRepair {

    /** Tool names other harnesses use → FARAI's tool. Used only when no tool has that exact name. */
    val TOOL_ALIASES: Map<String, String> = mapOf(
        "shell" to "bash", "run_shell" to "bash", "execute" to "bash", "exec" to "bash", "terminal" to "bash",
        "run_command" to "bash", "execute_command" to "bash", "bash_command" to "bash", "sh" to "bash",
        "read_file" to "read", "view" to "read", "cat" to "read", "open_file" to "read", "ls" to "read", "list" to "read",
        "list_dir" to "read", "list_directory" to "read",
        "write_file" to "write", "create_file" to "write", "create" to "write", "save_file" to "write",
        "str_replace" to "edit", "str_replace_editor" to "edit", "replace" to "edit", "edit_file" to "edit",
        "replace_in_file" to "edit", "search_replace" to "edit",
        "multi_edit" to "multiedit", "multiedits" to "multiedit",
        "apply_patch" to "patch", "applypatch" to "patch", "apply_diff" to "patch",
        "todowrite" to "todo", "todo_write" to "todo", "todoread" to "todo", "todo_read" to "todo", "update_todos" to "todo",
        "websearch" to "web_search", "search_web" to "web_search", "web" to "web_search", "google" to "web_search", "google_search" to "web_search", "internet_search" to "web_search", "bing" to "web_search", "duckduckgo" to "web_search", "search_internet" to "web_search", "brave_search" to "web_search", "tavily_search" to "web_search",
        "webfetch" to "web_fetch", "fetch" to "web_fetch", "fetch_url" to "web_fetch", "browse" to "web_fetch",
        "search" to "grep", "grep_search" to "grep", "search_files" to "grep", "ripgrep" to "grep", "rg" to "grep",
        "find" to "glob", "find_files" to "glob", "file_search" to "glob",
        "subagent" to "task", "agent" to "task", "delegate" to "task", "dispatch_agent" to "task",
        "ask" to "question", "ask_user" to "question", "ask_question" to "question", "askuserquestion" to "question",
        "done" to "finish", "complete" to "finish", "attempt_completion" to "finish", "task_complete" to "finish",
        "load_skill" to "skill", "use_skill" to "skill",
        "bash_output" to "job", "kill_shell" to "job", "killshell" to "job",
    )

    /** Argument names other harnesses use → FARAI's. Applied only when the tool has the target and lacks the alias. */
    private val ARG_ALIASES: Map<String, String> = mapOf(
        "file_path" to "path", "filepath" to "path", "filename" to "path", "file" to "path", "target_file" to "path",
        "absolute_path" to "path", "dir" to "path", "directory" to "path", "dir_path" to "path",
        "cmd" to "command", "script" to "command",
        "contents" to "content", "text" to "content", "file_text" to "content", "data" to "content",
        "old_str" to "old_string", "old" to "old_string", "search" to "old_string", "find" to "old_string",
        "new_str" to "new_string", "new" to "new_string", "replacement" to "new_string", "replace" to "new_string",
        "replaceall" to "replace_all", "all" to "replace_all",
        "query" to "pattern", "regex" to "pattern", "glob" to "include", "file_pattern" to "include",
        "todos" to "items", "tasks" to "items",
        "cwd" to "workdir", "working_directory" to "workdir",
        "timeout" to "timeout_seconds", "timeout_ms" to "timeout_seconds",
        "run_in_background" to "background",
        "subagent_type" to "agent", "agent_type" to "agent", "session_id" to "task_id",
        "start_line" to "offset", "line" to "offset", "lines" to "limit", "max_lines" to "limit",
        "message" to "summary", "result" to "summary",
        "diff" to "patch", "patch_text" to "patch", "patchtext" to "patch", "input" to "patch",
        "shell_id" to "id", "bash_id" to "id", "job_id" to "id",
    )

    data class Repaired(val args: JsonObject, val notes: List<String>)

    fun resolveToolName(called: String, available: Collection<String>): String? {
        if (called in available) return called
        val lower = called.trim().lowercase().removePrefix("functions.").removePrefix("tools.")
        available.firstOrNull { it.lowercase() == lower }?.let { return it }
        val snake = camelToSnake(called).lowercase()
        available.firstOrNull { it == snake }?.let { return it }
        return (TOOL_ALIASES[lower] ?: TOOL_ALIASES[snake])?.takeIf { it in available }
    }

    /** Parses tool-call arguments, forgiving fences, trailing commas and double encoding. Null = hopeless. */
    fun parseArguments(raw: String): JsonObject? {
        var text = raw.trim().ifEmpty { "{}" }
        if (text.startsWith("```")) text = text.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
        fun attempt(s: String): JsonObject? = runCatching {
            when (val el = HarnessJson.parseToJsonElement(s)) {
                is JsonObject -> el
                is JsonPrimitive -> if (el.isString) HarnessJson.parseToJsonElement(el.content).jsonObject else null
                else -> null
            }
        }.getOrNull()
        attempt(text)?.let { return it }
        attempt(text.replace(Regex(",\\s*([}\\]])"), "$1"))?.let { return it }
        return null
    }

    fun repair(args: JsonObject, schema: JsonObject, tool: String): Repaired {
        val props = (schema["properties"] as? JsonObject) ?: return Repaired(args, emptyList())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        val notes = ArrayList<String>()
        val out = LinkedHashMap<String, JsonElement>()
        for ((rawKey, value) in args) {
            var key = rawKey
            if (key !in props) {
                val snake = camelToSnake(key).lowercase()
                key = when {
                    snake in props -> snake
                    ARG_ALIASES[snake]?.let { it in props && !args.containsKey(it) } == true -> ARG_ALIASES.getValue(snake)
                    else -> key
                }
                if (key != rawKey) notes += "`$rawKey` → `$key`"
            }
            var v = value
            if (key == "timeout_seconds" && (rawKey == "timeout" || rawKey == "timeout_ms" || rawKey == "timeoutMs")) {
                // Other harnesses take milliseconds.
                (v as? JsonPrimitive)?.longOrNull?.let { n -> if (rawKey != "timeout" || n > 1800) v = JsonPrimitive((n / 1000).coerceAtLeast(1)) }
            }
            val propSchema = props[key] as? JsonObject
            if (propSchema == null) {
                if (!closed) out[key] = v
                continue
            }
            if (v is JsonNull) { if (key in required) out[key] = v; continue }
            out[key] = coerce(v, propSchema)
        }
        return Repaired(JsonObject(out), notes)
    }

    private fun coerce(v: JsonElement, schema: JsonObject): JsonElement {
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull ?: return v
        return when (type) {
            "integer" -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.let { s ->
                s.toLongOrNull()?.let { JsonPrimitive(it) } ?: s.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.let { JsonPrimitive(it.toLong()) }
            } ?: (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it % 1.0 == 0.0 && v.toString().contains('.') }?.let { JsonPrimitive(it.toLong()) } ?: v
            "number" -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: v
            "boolean" -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()?.let {
                when (it) { "true", "yes", "1" -> JsonPrimitive(true); "false", "no", "0" -> JsonPrimitive(false); else -> null }
            } ?: v
            "string" -> when {
                v is JsonPrimitive && !v.isString -> JsonPrimitive(v.content)
                v is JsonArray && v.all { it is JsonPrimitive } -> JsonPrimitive(v.joinToString("\n") { (it as JsonPrimitive).content })
                else -> v
            }
            "array" -> {
                val parsed = if (v is JsonPrimitive && v.isString) runCatching { HarnessJson.parseToJsonElement(v.content) }.getOrNull() else v
                val items = schema["items"] as? JsonObject
                when (parsed) {
                    is JsonArray -> if (items != null) JsonArray(parsed.map { coerce(it, items) }) else parsed
                    is JsonObject -> JsonArray(listOf(if (items != null) coerce(parsed, items) else parsed))
                    else -> v
                }
            }
            "object" -> {
                val parsed = if (v is JsonPrimitive && v.isString) runCatching { HarnessJson.parseToJsonElement(v.content) as? JsonObject }.getOrNull() else v as? JsonObject
                if (parsed is JsonObject) repair(parsed, schema, "").args else v
            }
            else -> v
        }
    }

    fun camelToSnake(s: String): String = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace('-', '_')
}
