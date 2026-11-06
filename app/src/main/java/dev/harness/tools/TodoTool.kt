package dev.harness.tools

import dev.harness.net.HarnessJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class TodoItem(val content: String, val status: String = PENDING) {
    companion object {
        const val PENDING = "pending"
        const val IN_PROGRESS = "in_progress"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
        val STATUSES = listOf(PENDING, IN_PROGRESS, DONE, CANCELLED)

        /** Accepts the spellings other harnesses use (completed, in-progress, canceled). */
        fun normalize(s: String?): String? = when (s?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
            "pending", "todo", "open" -> PENDING
            "in_progress", "active", "doing", "started" -> IN_PROGRESS
            "done", "completed", "complete", "finished" -> DONE
            "cancelled", "canceled", "skipped", "dropped" -> CANCELLED
            else -> null
        }
    }
}

/** Per-session todo list, observed by the chat screen's progress chip. */
class TodoStore(initial: List<TodoItem> = emptyList()) {
    private val _items = MutableStateFlow(initial)
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    fun set(list: List<TodoItem>) { _items.value = list }
    fun clear() { _items.value = emptyList() }
    val doneCount: Int get() = _items.value.count { it.status == TodoItem.DONE || it.status == TodoItem.CANCELLED }

    fun render(): String = if (_items.value.isEmpty()) "(empty)" else _items.value.mapIndexed { i, t ->
        val box = when (t.status) { TodoItem.DONE -> "[x]"; TodoItem.IN_PROGRESS -> "[~]"; TodoItem.CANCELLED -> "[-]"; else -> "[ ]" }
        "${i + 1}. $box ${t.content}"
    }.joinToString("\n")

    fun toJson(): String = HarnessJson.encodeToString(_items.value)

    companion object {
        fun fromJson(json: String): List<TodoItem> =
            runCatching { HarnessJson.decodeFromString<List<TodoItem>>(json) }.getOrDefault(emptyList())
    }
}

class TodoTool : Tool {
    override val name = "todo"
    override val parameters: JsonObject = schema {
        string("action", enum = listOf("read", "write"))
        raw("items", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("content") { put("type", "string") }
                    putJsonObject("status") {
                        put("type", "string")
                        putJsonArray("enum") { TodoItem.STATUSES.forEach { add(JsonPrimitive(it)) } }
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("content")); add(JsonPrimitive("status")) }
            }
        })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val items = args.array("items") ?: args.array("todos")
        val action = args.str("action") ?: if (items != null) "write" else "read"
        return when (action) {
            "read" -> ToolResult(ctx.todos.render())
            "write" -> {
                val arr = items ?: return ToolResult.error("'items' is required for write")
                val list = arr.map { el ->
                    val o = el.jsonObject
                    val content = o.str("content")?.trim().orEmpty()
                    val status = TodoItem.normalize(o.str("status") ?: "pending")
                        ?: return ToolResult.error("invalid status in item \"$content\"; use pending, in_progress, done or cancelled")
                    if (content.isEmpty()) return ToolResult.error("todo item content must not be empty")
                    TodoItem(content, status)
                }
                ctx.todos.set(list)
                val active = list.count { it.status == TodoItem.IN_PROGRESS }
                val warn = if (active > 1) "\nNote: $active items are in_progress; keep one at a time." else ""
                ToolResult("todo list updated (${ctx.todos.doneCount}/${list.size} closed)\n" + ctx.todos.render() + warn)
            }
            else -> ToolResult.error("action must be 'read' or 'write'")
        }
    }
}
