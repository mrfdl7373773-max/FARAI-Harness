package dev.harness.tools

import dev.harness.core.memory.MemoryException
import dev.harness.core.memory.MemoryRuntime
import kotlinx.serialization.json.JsonObject

/** `memory`: read and edit FARAI's long-term memory files (see core/memory). */
class MemoryTool(private val runtime: () -> MemoryRuntime?) : Tool {
    override val name = "memory"
    override val parallelSafe = false
    override val parameters: JsonObject = schema {
        string("action", required = true, enum = listOf("view", "create", "str_replace", "append", "insert", "rewrite", "delete", "move", "search", "history", "revert"))
        string("path")
        string("description")
        string("content")
        string("old_text")
        string("new_text")
        boolean("replace_all")
        string("text")
        integer("line")
        string("new_path")
        string("query")
        string("id")
        string("reason")
        integer("offset")
        integer("limit")
        boolean("overwrite")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rt = runtime() ?: return ToolResult.error("memory is not available")
        if (!rt.enabled()) return ToolResult.error("memory is turned off (Settings → Memory, or /memory on)")
        return try {
            ToolResult(rt.runMemoryTool(args, ctx.sessionId.ifBlank { null }))
        } catch (e: MemoryException) {
            ToolResult.error(e.message ?: "memory error")
        }
    }
}

/** `recall`: search everything said in past conversations, including what compaction removed. */
class RecallTool(private val runtime: () -> MemoryRuntime?) : Tool {
    override val name = "recall"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("query")
        string("after")
        string("before")
        string("role", enum = listOf("user", "assistant"))
        string("session")
        integer("around")
        integer("before_count")
        integer("after_count")
        integer("limit")
        boolean("include_current")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rt = runtime() ?: return ToolResult.error("recall is not available")
        return ToolResult(rt.runRecallTool(args, ctx.sessionId.ifBlank { null }))
    }
}
