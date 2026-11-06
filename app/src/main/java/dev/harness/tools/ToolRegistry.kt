package dev.harness.tools

import dev.harness.core.PersonaLoader
import dev.harness.net.FunctionSpec
import dev.harness.net.ToolSchema

/**
 * All tools the harness knows about. To add a custom tool: implement [Tool], add it to
 * [standard] (or call [register]), and create prompts/tools/<name>.md with its description.
 */
class ToolRegistry(tools: List<Tool>, private val dynamic: () -> List<Tool> = { emptyList() }) {
    private val tools = LinkedHashMap<String, Tool>().apply { tools.forEach { put(it.name, it) } }

    /** Built-in tools first, then tools that come and go (MCP servers); built-ins win a name clash. */
    val all: List<Tool> get() = tools.values.toList() + runCatching { dynamic() }.getOrDefault(emptyList()).filter { it.name !in tools }
    val names: List<String> get() = all.map { it.name }

    fun get(name: String): Tool? = tools[name] ?: runCatching { dynamic() }.getOrDefault(emptyList()).firstOrNull { it.name == name }

    fun register(tool: Tool) { tools[tool.name] = tool }

    /**
     * Builds the tool list sent to the model. Descriptions are read from the prompt files on
     * every call, so edits apply to the next request.
     */
    fun schemas(persona: PersonaLoader, include: (Tool) -> Boolean = { true }): List<ToolSchema> =
        all.filter(include).map { t ->
            ToolSchema(function = FunctionSpec(name = t.name, description = persona.toolDescription(t), parameters = t.parameters))
        }

    companion object {
        /** Tools that work without an Android Context (used by subagents and JVM tests). */
        fun core(shell: () -> ShellBackend? = { null }): List<Tool> = listOf(
            BashTool(shell), ReadTool(), WriteTool(), EditTool(), MultiEditTool(), PatchTool(), GlobTool(), GrepTool(),
            WebTool(), WebSearchTool(), TodoTool(), TaskTool(), QuestionTool(), JobTool(), SkillTool(), FinishTool(),
        )

        /** Memory tools; the runtime is created by AppContainer. */
        fun memory(runtime: () -> dev.harness.core.memory.MemoryRuntime?): List<Tool> = listOf(MemoryTool(runtime), RecallTool(runtime))

        fun android(): List<Tool> = listOf(ClipboardTool(), NotificationTool(), IntentTool(), DeviceInfoTool())

        fun standard(shell: () -> ShellBackend? = { null }, dynamic: () -> List<Tool> = { emptyList() }): ToolRegistry =
            ToolRegistry(core(shell) + android(), dynamic)

        /** Tools for a read-only explorer when no agent file says otherwise. */
        val SUBAGENT_DEFAULT = setOf("read", "glob", "grep", "bash", "web_fetch", "web_search", "skill")
    }
}
