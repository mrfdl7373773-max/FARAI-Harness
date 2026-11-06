package dev.harness.tools

import android.content.Context
import dev.harness.core.ConfirmationGate
import dev.harness.core.Safety
import dev.harness.data.settings.AgentSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * A tool the model can call. The description the model reads comes from the prompt file at
 * [promptPath]; only the JSON parameter schema lives in code.
 */
interface Tool {
    val name: String
    /** Prompt file holding the description, relative to the prompts folder. */
    val promptPath: String get() = "tools/$name.md"
    val parameters: JsonObject
    /** Read-only tools are the default subagent tool set candidates. */
    val readOnly: Boolean get() = false
    /** Description used when the prompt file at [promptPath] does not exist (e.g. MCP tools). */
    val fallbackDescription: String? get() = null
    /** Safe to run at the same time as other calls from the same reply. */
    val parallelSafe: Boolean get() = readOnly
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

data class ToolResult(
    val output: String,
    val isError: Boolean = false,
    /** True when the tool ends the agent loop (finish). */
    val finish: Boolean = false,
) {
    companion object {
        fun error(message: String) = ToolResult("ERROR: $message", isError = true)
    }
}

/** Runs a subagent; implemented by the Engine. [sessionId] continues an earlier subagent. */
fun interface SubagentRunner {
    suspend fun run(agent: String?, description: String, prompt: String, sessionId: String?): SubagentResult
}

data class SubagentResult(val text: String, val sessionId: String, val agent: String)

/**
 * Asks the permission engine (and, when a rule says "ask", the user). Returns null when the call
 * may go ahead, or the error text to return to the model.
 */
fun interface PermissionAsker {
    suspend fun check(action: String, resources: List<String>, title: String, detail: String, always: List<String>): String?
}

class ToolContext(
    val workspace: Workspace,
    val safety: Safety,
    val agent: AgentSettings,
    val todos: TodoStore,
    val confirm: ConfirmationGate,
    val http: OkHttpClient,
    val android: Context? = null,
    val subagent: SubagentRunner? = null,
    /** Per-session bookkeeping shared by tools (reads, loaded instructions, snapshots). */
    val session: ToolSession = ToolSession(),
    val permission: PermissionAsker = PermissionAsker { _, _, _, _, _ -> null },
    val question: dev.harness.core.QuestionGate? = null,
    val jobs: JobManager? = null,
    val sessionId: String = "",
    /** Agents the task tool can start (name to description). */
    val subagentNames: () -> List<Pair<String, String>> = { emptyList() },
) {
    /** Call before changing a file so /undo can put it back. */
    fun beforeMutate(file: java.io.File) = session.snapshot?.invoke(file)
}

/**
 * State tools keep for one session: which files the model has seen (so `write` will not blindly
 * replace a file it never read), which AGENTS.md files were already injected, and the snapshot hook.
 */
class ToolSession {
    val seen: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    val instructions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var snapshot: ((java.io.File) -> Unit)? = null

    fun markSeen(f: java.io.File) { seen[f.canonicalPath] = f.lastModified() }
    fun hasSeen(f: java.io.File) = seen.containsKey(f.canonicalPath)
}

// ---- Schema + argument helpers -------------------------------------------------------------

class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, required: Boolean = false, enum: List<String>? = null) = prop(name, required) {
        put("type", "string")
        if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
    }
    fun integer(name: String, required: Boolean = false) = prop(name, required) { put("type", "integer") }
    fun boolean(name: String, required: Boolean = false) = prop(name, required) { put("type", "boolean") }
    fun number(name: String, required: Boolean = false) = prop(name, required) { put("type", "number") }
    fun array(name: String, items: JsonObject, required: Boolean = false) = prop(name, required) {
        put("type", "array")
        put("items", items)
    }
    fun raw(name: String, schema: JsonObject, required: Boolean = false) {
        props[name] = schema
        if (required) this.required += name
    }

    private fun prop(name: String, req: Boolean, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        props[name] = buildJsonObject(block)
        if (req) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

class ToolArgumentException(message: String) : IllegalArgumentException(message)

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.reqStr(key: String): String = str(key) ?: throw ToolArgumentException("missing required argument '$key'")
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
fun JsonElement.asString(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
