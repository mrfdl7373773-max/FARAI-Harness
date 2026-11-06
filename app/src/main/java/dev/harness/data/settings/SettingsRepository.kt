package dev.harness.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.harnessDataStore: DataStore<Preferences> by preferencesDataStore(name = "farai_settings")

/**
 * Non-secret settings live in DataStore; the API key lives in [SecurePrefs].
 * Every update is mirrored to filesDir/config.toml (without the key).
 */
class SettingsRepository(private val context: Context) {

    private val store = context.harnessDataStore
    private val secure by lazy { SecurePrefs(context) }
    val configFile: File get() = File(context.filesDir, "config.toml")

    @Volatile private var lastKnown: AppSettings? = null

    val settings: Flow<AppSettings> = store.data.map { fromPrefs(it).also { s -> lastKnown = s } }

    /** The most recently read settings, for non-suspending callers; null before the first read. */
    fun currentOrNull(): AppSettings? = lastKnown

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val before = current()
        val after = transform(before)
        if (after.provider.apiKey != before.provider.apiKey) {
            withContext(Dispatchers.IO) { secure.apiKey = after.provider.apiKey }
        }
        store.edit { write(it, after) }
        mirror(after)
    }

    suspend fun importToml(text: String) {
        update { ConfigToml.decode(text, it) }
    }

    suspend fun exportToml(): String = ConfigToml.encode(current())

    private suspend fun mirror(s: AppSettings) = withContext(Dispatchers.IO) {
        runCatching { configFile.writeText(ConfigToml.encode(s)) }
    }

    private fun splitList(v: String?): List<String>? = v?.split('\n')?.map { it.trimEnd() }?.filter { it.isNotEmpty() }

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            provider = ProviderSettings(
                baseUrl = p[K.baseUrl] ?: d.provider.baseUrl,
                apiKey = runCatching { secure.apiKey }.getOrDefault(""),
                model = p[K.model] ?: d.provider.model,
                temperature = p[K.temperature] ?: d.provider.temperature,
                maxTokens = p[K.maxTokensV2] ?: p[K.maxTokens]?.takeUnless { it == ProviderSettings.LEGACY_MAX_TOKENS } ?: d.provider.maxTokens,
                maxTurns = p[K.maxTurns] ?: d.provider.maxTurns,
                timeoutSeconds = p[K.timeout] ?: d.provider.timeoutSeconds,
                contextWindow = p[K.contextWindow] ?: d.provider.contextWindow,
                reasoningEffort = p[K.reasoningEffort] ?: d.provider.reasoningEffort,
            ),
            agent = AgentSettings(
                repoMap = p[K.repoMap] ?: d.agent.repoMap,
                compaction = p[K.compaction] ?: d.agent.compaction,
                contextThreshold = p[K.contextThreshold] ?: d.agent.contextThreshold,
                subagents = p[K.subagents] ?: d.agent.subagents,
                autoPlanning = p[K.autoPlanning] ?: d.agent.autoPlanning,
                bashTimeoutSeconds = p[K.bashTimeout] ?: d.agent.bashTimeoutSeconds,
                bashOutputCap = p[K.bashCap] ?: d.agent.bashOutputCap,
                useLinux = p[K.useLinux] ?: d.agent.useLinux,
                parallelTools = p[K.parallelTools] ?: d.agent.parallelTools,
                autoTitle = p[K.autoTitle] ?: d.agent.autoTitle,
                goalMaxRounds = p[K.goalMaxRounds] ?: d.agent.goalMaxRounds,
                approvalMode = p[K.approvalMode] ?: d.agent.approvalMode,
                memory = p[K.memory] ?: d.agent.memory,
                reflectEvery = p[K.reflectEvery] ?: d.agent.reflectEvery,
                reflectionModel = p[K.reflectionModel] ?: d.agent.reflectionModel,
            ),
            safety = SafetySettings(
                allowlist = splitList(p[K.allowlist]) ?: d.safety.allowlist,
                blocklist = splitList(p[K.blocklist]) ?: d.safety.blocklist,
                confirmList = splitList(p[K.confirmList]) ?: d.safety.confirmList,
            ),
            ui = UiSettings(
                theme = p[K.theme]?.let { t -> ThemeMode.entries.firstOrNull { it.name == t } } ?: d.ui.theme,
                fontScale = p[K.fontScale] ?: d.ui.fontScale,
                markdown = p[K.markdown] ?: d.ui.markdown,
                dynamicColor = p[K.dynamicColor] ?: d.ui.dynamicColor,
                language = p[K.language] ?: d.ui.language,
            ),
            onboardingComplete = p[K.onboarding] ?: false,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[K.baseUrl] = s.provider.baseUrl
        p[K.model] = s.provider.model
        p[K.temperature] = s.provider.temperature
        p[K.maxTokensV2] = s.provider.maxTokens
        p[K.maxTurns] = s.provider.maxTurns
        p[K.timeout] = s.provider.timeoutSeconds
        p[K.contextWindow] = s.provider.contextWindow
        p[K.reasoningEffort] = s.provider.reasoningEffort
        p[K.repoMap] = s.agent.repoMap
        p[K.compaction] = s.agent.compaction
        p[K.contextThreshold] = s.agent.contextThreshold
        p[K.subagents] = s.agent.subagents
        p[K.autoPlanning] = s.agent.autoPlanning
        p[K.bashTimeout] = s.agent.bashTimeoutSeconds
        p[K.bashCap] = s.agent.bashOutputCap
        p[K.useLinux] = s.agent.useLinux
        p[K.parallelTools] = s.agent.parallelTools
        p[K.autoTitle] = s.agent.autoTitle
        p[K.goalMaxRounds] = s.agent.goalMaxRounds
        p[K.approvalMode] = s.agent.approvalMode
        p[K.memory] = s.agent.memory
        p[K.reflectEvery] = s.agent.reflectEvery
        p[K.reflectionModel] = s.agent.reflectionModel
        // Empty lists are stored as "" so that "user emptied the list" differs from "never set".
        p[K.allowlist] = s.safety.allowlist.joinToString("\n")
        p[K.blocklist] = s.safety.blocklist.joinToString("\n")
        p[K.confirmList] = s.safety.confirmList.joinToString("\n")
        p[K.theme] = s.ui.theme.name
        p[K.fontScale] = s.ui.fontScale
        p[K.markdown] = s.ui.markdown
        p[K.dynamicColor] = s.ui.dynamicColor
        p[K.language] = s.ui.language
        p[K.onboarding] = s.onboardingComplete
    }

    private object K {
        val baseUrl = stringPreferencesKey("provider.base_url")
        val model = stringPreferencesKey("provider.model")
        val temperature = floatPreferencesKey("provider.temperature")
        val maxTokens = intPreferencesKey("provider.max_tokens")
        val maxTokensV2 = intPreferencesKey("provider.max_tokens_v2")
        val maxTurns = intPreferencesKey("provider.max_turns")
        val timeout = intPreferencesKey("provider.timeout")
        val contextWindow = intPreferencesKey("provider.context_window")
        val reasoningEffort = stringPreferencesKey("provider.reasoning_effort")
        val repoMap = booleanPreferencesKey("agent.repo_map")
        val compaction = booleanPreferencesKey("agent.compaction")
        val contextThreshold = floatPreferencesKey("agent.context_threshold")
        val subagents = booleanPreferencesKey("agent.subagents")
        val autoPlanning = booleanPreferencesKey("agent.auto_planning")
        val bashTimeout = intPreferencesKey("agent.bash_timeout")
        val bashCap = intPreferencesKey("agent.bash_output_cap")
        val useLinux = booleanPreferencesKey("agent.use_linux")
        val parallelTools = booleanPreferencesKey("agent.parallel_tools")
        val autoTitle = booleanPreferencesKey("agent.auto_title")
        val goalMaxRounds = intPreferencesKey("agent.goal_max_rounds")
        val approvalMode = stringPreferencesKey("agent.approval_mode")
        val memory = booleanPreferencesKey("agent.memory")
        val reflectEvery = intPreferencesKey("agent.reflect_every")
        val reflectionModel = stringPreferencesKey("agent.reflection_model")
        val allowlist = stringPreferencesKey("safety.allowlist")
        val blocklist = stringPreferencesKey("safety.blocklist")
        val confirmList = stringPreferencesKey("safety.confirm_list")
        val theme = stringPreferencesKey("ui.theme")
        val fontScale = floatPreferencesKey("ui.font_scale")
        val markdown = booleanPreferencesKey("ui.markdown")
        val dynamicColor = booleanPreferencesKey("ui.dynamic_color")
        val language = stringPreferencesKey("ui.language")
        val onboarding = booleanPreferencesKey("app.onboarding_complete")
    }
}
