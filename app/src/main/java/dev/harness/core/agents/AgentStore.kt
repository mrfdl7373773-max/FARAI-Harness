package dev.harness.core.agents

import dev.harness.core.PromptRepository
import dev.harness.core.permission.PermRule
import dev.harness.core.permission.PermissionRules
import dev.harness.core.skills.SkillStore
import java.io.File

/** Where an agent can run: picked by the user (primary), spawned by the task tool (subagent), or both. */
enum class AgentKind { PRIMARY, SUBAGENT, ALL;
    val canBePrimary get() = this != SUBAGENT
    val canBeSubagent get() = this != PRIMARY

    companion object {
        fun parse(s: String?): AgentKind = when (s?.trim()?.lowercase()) {
            "subagent", "sub" -> SUBAGENT
            "all", "both" -> ALL
            else -> PRIMARY
        }
    }
}

/**
 * One agent, defined by a Markdown file with YAML front matter. Everything about it is editable:
 *
 * ```
 * ---
 * name: plan
 * description: Read-only agent for exploring and planning before implementation.
 * mode: primary            # primary | subagent | all
 * tools: "*, -write, -edit" # "*" = every tool; "-name" removes one; or an explicit list
 * permission: |            # extra rules: <action> <pattern> <allow|ask|deny>, last match wins
 *   edit * deny
 *   edit .farai/plans/(any) allow
 * max_steps: 40            # optional; defaults to Settings → Max turns
 * temperature: 0.1         # optional
 * model: some/model        # optional; overrides the provider model for this agent
 * verify: false            # true = run the verify.md self-check before the final answer
 * reminder: |              # optional; injected as a <system-reminder> when the user switches to it
 *   You are in Plan mode...
 * ---
 * Instructions. For a primary agent they are appended to the system message; for a subagent they
 * are its whole job description.
 * ```
 */
data class AgentDef(
    val name: String,
    val description: String,
    val kind: AgentKind,
    val tools: ToolSelector,
    val permissions: List<PermRule>,
    val prompt: String,
    val maxSteps: Int? = null,
    val temperature: Float? = null,
    val model: String? = null,
    val verify: Boolean = false,
    val reminder: String? = null,
    val hidden: Boolean = false,
    /** Where it came from: "prompts/agents/plan.md" or ".farai/agents/x.md". */
    val source: String,
) {
    val label: String get() = name.replaceFirstChar { it.uppercase() }
}

/** "*", "*, -write", "read, glob, grep" → a filter over tool names. */
data class ToolSelector(val all: Boolean, val include: Set<String>, val exclude: Set<String>) {
    fun allows(tool: String): Boolean = tool !in exclude && (all || tool in include)

    companion object {
        val ALL = ToolSelector(true, emptySet(), emptySet())

        fun parse(raw: String?): ToolSelector {
            val items = raw?.trim()?.removeSurrounding("[", "]")?.split(',', ' ', '\n')
                ?.map { it.trim().trim('"', '\'') }?.filter { it.isNotEmpty() } ?: return ALL
            if (items.isEmpty()) return ALL
            val exclude = items.filter { it.startsWith("-") || it.startsWith("!") }.map { it.drop(1) }.toSet()
            val include = items.filterNot { it.startsWith("-") || it.startsWith("!") }.toSet()
            val all = "*" in include || include.isEmpty()
            return ToolSelector(all, include - "*", exclude)
        }
    }
}

/**
 * Loads agents from `prompts/agents/<name>.md` (shipped defaults, editable in Settings → Prompts)
 * and from the workspace (`.farai/agents/<name>.md`, and `.claude/agents/<name>.md` for compatibility), so a project can carry its own agents. Read fresh on every call.
 */
class AgentStore(
    private val prompts: PromptRepository,
    private val workspaceRoot: () -> File?,
) {
    fun all(): List<AgentDef> {
        val byName = LinkedHashMap<String, AgentDef>()
        prompts.list().filter { it.exists && it.path.startsWith("agents/") }.forEach { pf ->
            runCatching { parse(prompts.read(pf.path), pf.path.substringAfterLast('/').removeSuffix(".md"), "prompts/${pf.path}") }
                .getOrNull()?.let { byName[it.name.lowercase()] = it }
        }
        workspaceRoot()?.let { root ->
            for (dir in WORKSPACE_DIRS) {
                File(root, dir).listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.sortedBy { it.name }?.forEach { f ->
                    runCatching { parse(f.readText(), f.nameWithoutExtension, "$dir/${f.name}") }.getOrNull()
                        ?.let { byName[it.name.lowercase()] = it }
                }
            }
        }
        return byName.values.toList()
    }

    fun get(name: String?): AgentDef? {
        val n = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return all().firstOrNull { it.name.lowercase() == n }
    }

    /** Agents the user can pick in the chat, Build first. */
    fun primary(): List<AgentDef> = all().filter { it.kind.canBePrimary && !it.hidden }
        .sortedWith(compareBy({ ORDER.indexOf(it.name.lowercase()).let { i -> if (i < 0) 99 else i } }, { it.name }))

    fun subagents(): List<AgentDef> = all().filter { it.kind.canBeSubagent && !it.hidden }

    /** The agent to use for [name], falling back to Build (or a minimal built-in when files are gone). */
    fun resolvePrimary(name: String?): AgentDef =
        get(name)?.takeIf { it.kind.canBePrimary } ?: get(DEFAULT) ?: FALLBACK

    companion object {
        const val DEFAULT = "build"
        val ORDER = listOf("build", "plan", "verify")
        val WORKSPACE_DIRS = listOf(".farai/agents", ".claude/agents")

        val FALLBACK = AgentDef(
            name = DEFAULT, description = "Default agent", kind = AgentKind.PRIMARY, tools = ToolSelector.ALL,
            permissions = emptyList(), prompt = "", source = "built-in",
        )

        /** Maps the pre-2.0 session modes onto agents. */
        fun legacyName(mode: String?): String = when (mode?.trim()?.uppercase()) {
            null, "", "CHAT" -> "build"
            "PLANNING" -> "plan"
            "VERIFY" -> "verify"
            else -> mode.trim().lowercase()
        }

        fun parse(text: String, fallbackName: String, source: String): AgentDef {
            val fm = SkillStore.frontMatter(text)
            val body = SkillStore.splitFrontMatter(text).second.trim()
            val name = fm["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
            // Files in .claude/agents have no mode key: they are subagents.
            val kind = when {
                fm.containsKey("mode") -> AgentKind.parse(fm["mode"])
                source.startsWith(".claude/") -> AgentKind.SUBAGENT
                else -> AgentKind.PRIMARY
            }
            return AgentDef(
                name = name,
                description = fm["description"].orEmpty(),
                kind = kind,
                tools = ToolSelector.parse(fm["tools"]),
                permissions = PermissionRules.parse(fm["permission"] ?: fm["permissions"] ?: "", "agent:$name"),
                prompt = body,
                maxSteps = fm["max_steps"]?.toIntOrNull() ?: fm["steps"]?.toIntOrNull(),
                temperature = fm["temperature"]?.toFloatOrNull(),
                model = fm["model"]?.takeIf { it.isNotBlank() && it != "inherit" },
                verify = fm["verify"]?.trim()?.lowercase() == "true",
                reminder = fm["reminder"]?.takeIf { it.isNotBlank() },
                hidden = fm["hidden"]?.trim()?.lowercase() == "true",
                source = source,
            )
        }
    }
}
