package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command

/** Every built-in slash command, in /help order within each group. */
object Builtins {
    fun all(): List<Command> = listOf(
        // Required
        HelpCommand(), PromptCommand(), ConfigCommand(), QuitCommand(),
        // Recommended
        ModelCommand(), TodoCommand(), ClearCommand(), SaveCommand(), ExportCommand(),
        ToolsCommand(), AgentCommand(), ResetAllCommand(),
        // Harness-specific
        RenameCommand(), ForkCommand(), SessionsCommand(), TagCommand(), HistoryCommand(),
        ToolCommand(), RunCommand(), LastCommand(),
        TokensCommand(), CompactCommand(), ContextCommand(),
        PayloadCommand(), HeadersCommand(), HealthCommand(),
        TemplateCommand(), RetryCommand(), UndoCommand(), TempCommand(), TurnsCommand(), ReasoningCommand(),
        LinuxCommand(), PipCommand(), SkillsCommand(),
        GoalCommand(), InitCommand(), ReviewCommand(), PermissionsCommand(), JobsCommand(), McpCommand(), MemoryCommand(), RememberCommand(), RecallCommand(),
    )
}

internal fun fmtTime(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

internal fun fence(text: String, lang: String = ""): String {
    val ticks = if (text.contains("```")) "````" else "```"
    return "$ticks$lang\n${text.trimEnd()}\n$ticks"
}
