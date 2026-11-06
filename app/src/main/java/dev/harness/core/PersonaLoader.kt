package dev.harness.core

import dev.harness.core.agents.AgentDef
import dev.harness.core.agents.AgentStore
import dev.harness.core.permission.Wildcard
import dev.harness.core.skills.SkillStore
import dev.harness.tools.TodoItem
import dev.harness.tools.Tool

/** Facts about the runtime. Rendered programmatically into the environment block. */
data class EnvironmentInfo(
    val appVersion: String,
    val androidVersion: String,
    val apiLevel: Int,
    val abi: String,
    val device: String,
    val cwd: String,
    val model: String,
    val dateTime: String,
    val timeZone: String,
    /** Where the bash tool runs, e.g. "Alpine Linux 3.24.4 via PRoot" or "Android /system/bin/sh (toybox)". */
    val shell: String = "Android /system/bin/sh (toybox)",
    /** Shared storage the tools may reach, or null without All files access. */
    val sharedStorage: String? = null,
)

data class SystemContext(
    val env: EnvironmentInfo,
    val agent: AgentDef = AgentStore.FALLBACK,
    /** True when planning.md should be included (a task judged complex). */
    val includePlanning: Boolean,
    val todos: List<TodoItem> = emptyList(),
    val repoMap: String? = null,
    val toolNames: List<String> = emptyList(),
    /** Instructions connected MCP servers sent in their initialize reply (server name to text). */
    val mcpInstructions: List<Pair<String, String>> = emptyList(),
    /** Project rules: the workspace's AGENTS.md / CLAUDE.md, already read. */
    val instructions: List<Pair<String, String>> = emptyList(),
    val goal: Goal? = null,
    /** Compiled long-term memory (core blocks, index, deferred folders), or null when off. */
    val memory: String? = null,
)

/**
 * Resolves prompts into what the model sees. Every method reads the prompt files fresh from
 * [PromptRepository]; nothing is cached between calls.
 *
 * System message order:
 *   identity.md, system.md, refusal.md, error_recovery.md, <environment>, memory (memory.md + core
 *   memory blocks), project AGENTS.md,
 *   skills, the active agent's file, models/(name).md overlays, planning.md (when included),
 *   goal reminder (when a goal is active), verify.md (footer).
 */
class PersonaLoader(
    private val repo: PromptRepository,
    /** Enabled Agent Skills (name, description, folder) listed in the system prompt. */
    private val skills: () -> List<dev.harness.core.skills.Skill> = { emptyList() },
) {

    fun buildSystemMessage(c: SystemContext): String {
        val parts = mutableListOf<String>()
        parts += repo.read(IDENTITY)
        parts += repo.read(SYSTEM)
        parts += repo.read(REFUSAL)
        parts += repo.read(ERROR_RECOVERY)
        parts += renderEnvironment(c)
        c.memory?.takeIf { it.isNotBlank() }?.let { parts += it }
        renderInstructions(c.instructions)?.let { parts += it }
        if (c.toolNames.isEmpty() || "skill" in c.toolNames) renderSkills()?.let { parts += it }
        renderMcp(c.mcpInstructions)?.let { parts += it }
        renderAgent(c.agent)?.let { parts += it }
        modelOverlays(c.env.model).forEach { parts += it }
        if (c.includePlanning) parts += repo.read(PLANNING)
        c.goal?.takeIf { it.active }?.let { g -> goalPrompt(GOAL_REMINDER, g.objective)?.let { parts += it } }
        parts += repo.read(VERIFY)
        return parts.filter { it.isNotBlank() }.joinToString("\n\n") { it.trim() }
    }

    /**
     * A subagent gets identity.md, the shared subagent contract (subagent.md), its own agent file,
     * the environment and the project rules. It never sees the parent conversation.
     */
    fun buildSubagentSystemMessage(env: EnvironmentInfo, toolNames: List<String>, agent: AgentDef? = null, instructions: List<Pair<String, String>> = emptyList(), memory: String? = null): String =
        listOfNotNull(
            repo.read(IDENTITY),
            repo.readOrNull(SUBAGENT) ?: repo.readOrNull(TASK_TOOL),
            agent?.prompt?.takeIf { it.isNotBlank() }?.let { "# Your role: ${agent.name}\n\n$it" },
            renderEnvironment(SystemContext(env, agent ?: AgentStore.FALLBACK, includePlanning = false, toolNames = toolNames)),
            renderInstructions(instructions),
            memory?.takeIf { it.isNotBlank() },
            *modelOverlays(env.model).toTypedArray(),
        ).filter { it.isNotBlank() }.joinToString("\n\n") { it.trim() }

    /** Server-provided usage notes for connected MCP servers. */
    fun renderMcp(list: List<Pair<String, String>>): String? {
        if (list.isEmpty()) return null
        return buildString {
            appendLine("# MCP servers")
            appendLine("Tools named `<server>_<tool>` come from these connected MCP servers. Their notes:")
            appendLine("<mcp_instructions>")
            list.forEach { (name, text) -> appendLine("<server name=\"$name\">"); appendLine(text.trim().take(4000)); appendLine("</server>") }
            append("</mcp_instructions>")
        }
    }

    private fun renderAgent(a: AgentDef): String? {
        if (a.prompt.isBlank()) return null
        return "# Active agent: ${a.name}\n\n${a.prompt.trim()}"
    }

    fun renderInstructions(list: List<Pair<String, String>>): String? {
        if (list.isEmpty()) return null
        return buildString {
            appendLine("# Project instructions")
            appendLine("These files come from the user's project. Follow them; they override general guidance above when they conflict.")
            list.forEach { (path, text) -> appendLine(); appendLine("Instructions from: $path"); appendLine(text.trim()) }
        }.trimEnd()
    }

    /**
     * prompts/models/(name).md files whose `match:` patterns fit the model id (e.g. `match: gpt-*, o3*`).
     * They tune the prompt for a model family without touching anything else.
     */
    fun modelOverlays(model: String): List<String> {
        val id = model.lowercase()
        val short = id.substringAfterLast('/')
        return repo.list().filter { it.exists && it.path.startsWith("models/") }.mapNotNull { pf ->
            val text = repo.readOrNull(pf.path) ?: return@mapNotNull null
            val patterns = SkillStore.frontMatter(text)["match"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (patterns.any { p -> Wildcard.match(id, p) || Wildcard.match(short, p) }) SkillStore.splitFrontMatter(text).second.trim() else null
        }
    }

    fun readOptional(path: String): String? = repo.readOrNull(path)?.trim()?.takeIf { it.isNotEmpty() }

    /** Reads a goal prompt and fills in its placeholders. */
    fun goalPrompt(path: String, objective: String, feedback: String = ""): String? =
        readOptional(path)?.replace("{{objective}}", objective.take(Goal.MAX_OBJECTIVE))?.replace("{{feedback}}", feedback)

    /** skills.md followed by one line per enabled skill, or null when there are none. */
    fun renderSkills(): String? {
        val list = runCatching { skills() }.getOrDefault(emptyList())
        if (list.isEmpty()) return null
        val intro = repo.readOrNull(SKILLS)?.trim() ?: return null
        return buildString {
            appendLine(intro)
            appendLine("<skills>")
            list.forEach { s -> appendLine("- ${s.name}: ${s.description.replace('\n', ' ')} (${s.source}/${s.dir.name}/SKILL.md)") }
            append("</skills>")
        }
    }

    fun toolDescription(tool: Tool): String =
        repo.readOrNull(tool.promptPath)?.trim()?.takeIf { it.isNotEmpty() } ?: tool.fallbackDescription ?: "The ${tool.name} tool. (Its description file prompts/${tool.promptPath} is missing; restore it in Settings → Prompts.)"

    fun compactionInstructions(): String = repo.read(COMPACTION)
    fun verifyInstructions(): String = repo.read(VERIFY)
    fun onboarding(): String = repo.read(ONBOARDING)

    fun templateNames(): List<String> = repo.list().filter { it.path.startsWith("templates/") && it.exists }.map { it.name }

    /** Expands templates/<name>.md, replacing {{args}} with the given arguments. */
    fun expandTemplate(name: String, args: String): String {
        val text = repo.read("templates/${name.removeSuffix(".md")}.md")
        return text.replace("{{args}}", args.ifBlank { "(none given)" })
    }

    fun renderEnvironment(c: SystemContext): String = buildString {
        appendLine("<environment>")
        appendLine("app: FARAI Harness ${c.env.appVersion}")
        appendLine("platform: Android ${c.env.androidVersion} (API ${c.env.apiLevel}), ABI ${c.env.abi}")
        appendLine("device: ${c.env.device}")
        appendLine("workspace: ${c.env.cwd}")
        appendLine("shell: ${c.env.shell}")
        appendLine("shared storage: ${c.env.sharedStorage ?: "not granted (only the workspace is reachable)"}")
        appendLine("model: ${c.env.model}")
        appendLine("agent: ${c.agent.name}")
        appendLine("date: ${Clock.nowLine()}")
        if (c.toolNames.isNotEmpty()) appendLine("tools: ${c.toolNames.joinToString(", ")}")
        appendLine("</environment>")
        if (c.todos.isNotEmpty()) {
            appendLine()
            appendLine("<todo closed=\"${c.todos.count { it.status == TodoItem.DONE || it.status == TodoItem.CANCELLED }}\" total=\"${c.todos.size}\">")
            c.todos.forEachIndexed { i, t -> appendLine("${i + 1}. [${t.status}] ${t.content}") }
            appendLine("</todo>")
        }
        if (!c.repoMap.isNullOrBlank()) {
            appendLine()
            appendLine("<repo_map>")
            appendLine(c.repoMap.trim())
            appendLine("</repo_map>")
        }
    }.trimEnd()

    companion object {
        const val IDENTITY = "identity.md"
        const val SYSTEM = "system.md"
        const val PLANNING = "planning.md"
        const val VERIFY = "verify.md"
        const val REFUSAL = "refusal.md"
        const val ERROR_RECOVERY = "error_recovery.md"
        const val COMPACTION = "compaction.md"
        const val ONBOARDING = "onboarding.md"
        const val TASK_TOOL = "tools/task.md"
        const val SUBAGENT = "subagent.md"
        const val SKILLS = "skills.md"
        const val MAX_STEPS = "max_steps.md"
        const val TITLE = "title.md"
        const val GOAL_KICKOFF = "goal/kickoff.md"
        const val GOAL_CONTINUE = "goal/continuation.md"
        const val GOAL_JUDGE = "goal/judge.md"
        const val GOAL_REMINDER = "goal/reminder.md"

        /** Heuristic for "this task is complex": long, multi-line, or enumerates several steps. */
        fun looksComplex(input: String): Boolean {
            val t = input.trim()
            if (t.length > 400) return true
            if (t.lines().count { it.isNotBlank() } >= 4) return true
            val steps = Regex("(?m)^\\s*(\\d+[.)]|[-*])\\s+").findAll(t).count()
            return steps >= 3
        }
    }
}
