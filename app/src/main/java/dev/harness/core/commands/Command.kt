package dev.harness.core.commands

import dev.harness.core.PersonaLoader
import dev.harness.core.PromptRepository
import dev.harness.core.SessionState
import dev.harness.data.settings.AppSettings
import dev.harness.tools.ToolRegistry
import dev.harness.tools.ToolResult

enum class CommandGroup(val label: String) {
    GENERAL("General"),
    SESSION("Session"),
    PROMPTS("Prompts"),
    MODEL("Model"),
    TOOLS("Tools"),
    CONTEXT("Context"),
    WORKFLOW("Workflow"),
    DEBUG("Debug"),
}

/** A parsed slash command line: "/prompt show identity" → name=prompt, args=[show, identity]. */
data class Invocation(val name: String, val args: List<String>, val rawArgs: String) {
    fun arg(i: Int): String? = args.getOrNull(i)
    /** Everything after the first [n] arguments, as typed. */
    fun rest(n: Int): String {
        var s = rawArgs.trimStart()
        repeat(n) {
            s = s.trimStart()
            s = if (s.startsWith("\"")) s.substringAfter('"').substringAfter('"', "") else s.substringAfter(' ', "")
        }
        return s.trim()
    }
}

/** Where a command can send the user. */
sealed interface NavTarget {
    data object Sessions : NavTarget
    data object Settings : NavTarget
    data object Provider : NavTarget
    data object Prompts : NavTarget
    data object Linux : NavTarget
    data class PromptEditor(val path: String, val showDiff: Boolean = false) : NavTarget
    data class Session(val id: String) : NavTarget
    /** Opens a file, folder or zip in the in-app viewer. */
    data class OpenFile(val path: String) : NavTarget
}

sealed interface CommandResult {
    data class Output(val text: String, val markdown: Boolean = true) : CommandResult
    data class Error(val text: String) : CommandResult
    data class Navigate(val target: NavTarget, val note: String? = null) : CommandResult
    data class Share(val title: String, val text: String) : CommandResult
    /** Sends [text] to the agent as if the user typed it. */
    data class SendToAgent(val text: String, val note: String? = null) : CommandResult
    data object None : CommandResult
}

data class SessionSummary(val id: String, val title: String, val updatedAt: Long, val messageCount: Int, val tags: List<String>, val model: String)

/**
 * What commands may touch. Implemented by the chat screen's ViewModel; tests use a fake.
 * Commands are for the human operator; they never talk to the model on their own except
 * through [CommandResult.SendToAgent] or the explicit /compact and /retry operations.
 */
interface CommandContext {
    val registry: CommandRegistry
    val prompts: PromptRepository
    val persona: PersonaLoader
    val tools: ToolRegistry
    val state: SessionState

    val linux: LinuxControl? get() = null
    /** Agent definitions (prompts/agents and the workspace). Null in tests without them. */
    val agents: dev.harness.core.agents.AgentStore? get() = null
    val goals: dev.harness.core.GoalStore? get() = null
    val jobs: dev.harness.tools.JobManager? get() = null
    val mcp: dev.harness.core.mcp.McpManager? get() = null
    /** Long-term memory. Null in tests without it. */
    val memory: dev.harness.core.memory.MemoryRuntime? get() = null
    /** Switches the session's primary agent (and tells the UI). */
    suspend fun setAgent(name: String) { state.agent = name; saveSession() }
    /** Tells the UI the goal changed. */
    fun goalChanged(goal: dev.harness.core.Goal?) {}
    /** Workspace folder (skills live in .farai/skills inside it). Null in tests without one. */
    val workspaceRoot: java.io.File? get() = null
    /** Shared storage root when All files access is granted, for importing from /sdcard. */
    val sharedStorage: java.io.File? get() = null

    suspend fun settings(): AppSettings
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)

    suspend fun sessionTitle(): String
    suspend fun renameSession(title: String)
    suspend fun saveSession()
    suspend fun forkSession(upToMessages: Int?): String?
    suspend fun sessionTags(): List<String>
    suspend fun setSessionTags(tags: List<String>)
    suspend fun searchSessions(query: String): List<SessionSummary>
    suspend fun sessionsByTag(tag: String): List<SessionSummary>
    suspend fun transcript(): String
    /** Drops the conversation but keeps the session, todo list and settings. */
    suspend fun clearContext()
    /** Wipes messages, todos, mode and tool toggles; keeps provider config. */
    suspend fun resetSession()

    suspend fun systemMessagePreview(): String
    fun estimateTokens(): Int
    suspend fun compactNow(): String?
    suspend fun listModels(): List<String>
    suspend fun healthCheck(): String
    suspend fun runTool(name: String, argumentsJson: String): ToolResult
    /** Removes the last assistant reply (and tool traffic) and runs the model again. */
    suspend fun retryLast(): Boolean
    /** Removes the last user message and everything after it. Returns messages removed. */
    suspend fun undoLast(): Int
    /** Files put back by the last [undoLast] (empty when none were tracked). */
    fun lastUndoRestored(): List<String> = emptyList()
}

/** What /linux may do with the Linux environment (Ubuntu or Alpine). Null in tests and when unavailable. */
interface LinuxControl {
    fun status(): String
    fun isReady(): Boolean
    /** Starts (or repairs) the install in the background and returns at once. [distro] null = the active one; [packages] null = its defaults. */
    fun startInstall(packages: List<String>?, distro: String? = null) {}
    /** Package manager of the active distro: "apt" or "apk". */
    fun packageManager(): String = "apk"
    /** Display name of the active distro. */
    fun distroName(): String = "Linux"
    suspend fun run(command: String, timeoutSeconds: Long, cap: Int): String
    suspend fun remove()
}

interface Command {
    val name: String
    val aliases: List<String> get() = emptyList()
    val group: CommandGroup
    /** Argument synopsis for /help, e.g. "<list|show|edit|diff|reset> [name]". */
    val usage: String get() = ""
    val description: String
    /** Sub-command words offered by autocomplete after "/name ". */
    val subcommands: List<String> get() = emptyList()
    suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult
}
