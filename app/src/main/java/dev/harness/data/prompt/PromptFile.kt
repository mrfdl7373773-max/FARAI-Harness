package dev.harness.data.prompt

/** Group shown in Settings → Prompts. */
enum class PromptGroup(val label: String, val order: Int) {
    IDENTITY("Identity", 0),
    SYSTEM("System", 1),
    AGENTS("Agents", 2),
    TOOLS("Tools", 3),
    BEHAVIOR("Behavior", 4),
    PERMISSIONS("Permissions & MCP", 5),
    MODELS("Model tuning", 6),
    TEMPLATES("Task templates", 7),
    ONBOARDING("Onboarding", 8),
    OTHER("Other", 9);

    companion object {
        fun of(path: String): PromptGroup = when {
            path == "identity.md" -> IDENTITY
            path == "system.md" -> SYSTEM
            path.startsWith("tools/") -> TOOLS
            path.startsWith("agents/") -> AGENTS
            path.startsWith("models/") -> MODELS
            path == "permissions.md" || path == "mcp.md" -> PERMISSIONS
            path.startsWith("goal/") -> BEHAVIOR
            path.startsWith("templates/") -> TEMPLATES
            path == "onboarding.md" -> ONBOARDING
            path in setOf("compaction.md", "planning.md", "verify.md", "refusal.md", "error_recovery.md", "max_steps.md", "title.md", "subagent.md", "skills.md") -> BEHAVIOR
            else -> OTHER
        }
    }
}

/** One editable prompt file under filesDir/prompts. [path] is relative, e.g. "tools/bash.md". */
data class PromptFile(
    val path: String,
    val group: PromptGroup,
    val sizeBytes: Long,
    val lastModified: Long,
    /** True when the file exists on disk. */
    val exists: Boolean,
    /** True when a shipped default exists for this path. */
    val hasDefault: Boolean,
    /** True when the on-disk content differs from the shipped default. */
    val modified: Boolean,
) {
    val name: String get() = path.substringAfterLast('/').removeSuffix(".md")
}

class PromptMissingException(val path: String) :
    IllegalStateException("Prompt file '$path' is missing. Restore it from defaults in Settings → Prompts.")
