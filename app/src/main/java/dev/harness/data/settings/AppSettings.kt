package dev.harness.data.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ProviderSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val temperature: Float = 0.2f,
    /** Output cap per model reply; 0 leaves it to the provider. Tool calls that hit it arrive cut off. */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val maxTurns: Int = 50,
    val timeoutSeconds: Int = 120,
    val contextWindow: Int = 128_000,
    /** Reasoning effort sent to the model (none…max), or "" to leave it to the model's default. */
    val reasoningEffort: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = ""
        const val DEFAULT_MODEL = ""
        const val DEFAULT_MAX_TOKENS = 16_384
        /** The old default; stored copies of it are upgraded to [DEFAULT_MAX_TOKENS]. */
        const val LEGACY_MAX_TOKENS = 4096
    }
}

data class AgentSettings(
    val repoMap: Boolean = false,
    val compaction: Boolean = true,
    val contextThreshold: Float = 0.8f,
    val subagents: Boolean = true,
    val autoPlanning: Boolean = true,
    val bashTimeoutSeconds: Int = 60,
    val bashOutputCap: Int = 30_000,
    /** Run the bash tool inside Alpine Linux (PRoot) when it is installed. */
    val useLinux: Boolean = true,
    /** Run read-only tool calls from one reply (reads, searches, subagents) at the same time. */
    val parallelTools: Boolean = true,
    /** Name new sessions with a short model-written title. */
    val autoTitle: Boolean = true,
    /** Most reviewer rounds a /goal may take before it pauses. */
    val goalMaxRounds: Int = 20,
    /** Long-term memory (core/memory): compiled into the prompt, memory + recall tools, reflection. */
    val memory: Boolean = true,
    /** Background reflection every N user messages (0 = only before compaction, -1 = never). */
    val reflectEvery: Int = 6,
    /** Model for reflection ("" = the chat model). */
    val reflectionModel: String = "",
    /**
     * How permission questions are answered: "ask" (follow the rules, ask when they say ask),
     * "edits" (file reads and edits go ahead, commands still ask), "always" (everything the rules
     * do not deny goes ahead without a question). Deny rules and the Safety blocklist always apply.
     */
    val approvalMode: String = "ask",
)

data class SafetySettings(
    val allowlist: List<String> = emptyList(),
    val blocklist: List<String> = DEFAULT_BLOCKLIST,
    val confirmList: List<String> = DEFAULT_CONFIRM,
) {
    companion object {
        val DEFAULT_BLOCKLIST = listOf(
            "rm -rf /",
            "rm -rf /*",
            "reboot",
            "/(^|\\s)su(\\s|$)/",
            ":(){ :|:& };:",
            "dd if=/dev/zero",
        )
        val DEFAULT_CONFIRM = listOf(
            "rm ",
            "mv ",
            "chmod ",
            "> /",
        )
    }
}

data class UiSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    val markdown: Boolean = true,
    val dynamicColor: Boolean = false,
    /** "en" or "id". */
    val language: String = "en",
)

data class AppSettings(
    val provider: ProviderSettings = ProviderSettings(),
    val agent: AgentSettings = AgentSettings(),
    val safety: SafetySettings = SafetySettings(),
    val ui: UiSettings = UiSettings(),
    val onboardingComplete: Boolean = false,
)
