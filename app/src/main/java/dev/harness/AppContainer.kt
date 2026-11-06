package dev.harness

import android.content.Context
import android.os.Build
import dev.harness.core.AssetDefaultsSource
import dev.harness.core.ContextCompactor
import dev.harness.core.Engine
import dev.harness.core.EnvironmentInfo
import dev.harness.core.PersonaLoader
import dev.harness.core.PromptRepository
import dev.harness.core.RepoMapper
import dev.harness.core.SessionManager
import dev.harness.core.commands.CommandRegistry
import dev.harness.core.commands.LinuxControl
import dev.harness.core.linux.LinuxRuntime
import dev.harness.core.linux.LinuxState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import dev.harness.core.linux.StorageAccess
import dev.harness.data.session.AppDatabase
import dev.harness.data.settings.SettingsRepository
import dev.harness.net.OpenAiClient
import dev.harness.tools.ToolRegistry
import dev.harness.tools.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Manual dependency graph, created once by [HarnessApp]. */
class AppContainer(val context: Context) {
    /** Outlives screens: long jobs such as the Linux install keep running while you navigate. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val prompts = PromptRepository(File(context.filesDir, "prompts"), AssetDefaultsSource(context.assets))
    val workspace = Workspace(File(context.filesDir, "workspace")) { listOfNotNull(StorageAccess.rootIfGranted(context)) }
    val skills = dev.harness.core.skills.SkillStore(workspace.root)
    val persona = PersonaLoader(prompts) { skills.enabled() }
    val database = AppDatabase.create(context)
    val sessions = SessionManager(database.sessions())

    @Volatile private var runTitle: String? = null

    /** Agent runs live here, not in a screen, so they continue in the background. */
    val runner: dev.harness.core.run.AgentRunner = dev.harness.core.run.AgentRunner(
        scope = appScope,
        save = { st -> sessions.save(st, settings.current().provider.model) },
        onActiveChanged = { _, t -> runTitle = t; syncService() },
        onBackgroundEvent = { id, kind ->
            appScope.launch {
                val title = runCatching { sessions.get(id)?.title }.getOrNull()
                dev.harness.core.run.AgentService.notifyBackground(context, id, title, kind)
            }
        },
    )
    val tools = ToolRegistry.standard(shell = { linux }, dynamic = { mcp.tools() }).apply {
        // Web search reads prompts/search.md on every call, so edits apply at once.
        register(dev.harness.tools.WebSearchTool { prompts.readOrNull("search.md") })
        dev.harness.tools.ToolRegistry.memory { memory }.forEach(::register)
    }
    val commands = CommandRegistry()
    val repoMapper = RepoMapper()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val linux = LinuxRuntime(context.applicationContext, http, workspace.root) { StorageAccess.rootIfGranted(context) }

    val linuxControl: LinuxControl = object : LinuxControl {
        override fun isReady() = linux.isReady
        override fun status(): String = when (val st = linux.state.value) {
            is LinuxState.Ready -> "**${st.release}** (PRoot, ${linux.arch}) · ready\nrootfs: ${linux.rootfs.absolutePath}\n" +
                "shared storage: " + (StorageAccess.rootIfGranted(context)?.let { "mounted at /sdcard" } ?: "not granted")
            is LinuxState.Working -> "Working: ${st.stage}"
            is LinuxState.Failed -> "Setup failed: ${st.message}"
            LinuxState.NotInstalled -> "Not installed. Run /linux install."
            is LinuxState.Unsupported -> "Unavailable: ${st.reason}"
        }
        override fun startInstall(packages: List<String>?, distro: String?) {
            val d = dev.harness.core.linux.Distro.parse(distro) ?: linux.active.value
            appScope.launch { linux.install(d, packages) }
        }
        override fun packageManager() = linux.active.value.packageManager
        override fun distroName() = linux.active.value.title
        override suspend fun run(command: String, timeoutSeconds: Long, cap: Int): String {
            val r = dev.harness.tools.BashTool.runProcess(kotlinx.coroutines.withContext(Dispatchers.IO) { linux.buildProcess(command) }, timeoutSeconds.toInt().coerceIn(1, 1800), cap, linux.label)
            return r.output
        }
        override suspend fun remove() = linux.uninstall()
    }

    val client = OpenAiClient(config = { settings.current().provider }, baseHttp = http)

    init {
        // Model calls and web tools pause while offline; the ongoing notification says so.
        dev.harness.net.Connectivity.start(context)
        appScope.launch { dev.harness.net.Connectivity.online.drop(1).collect { runCatching { syncService() } } }
    }
    val compactor = ContextCompactor(client, persona)

    /** Agents are Markdown files: prompts/agents (one .md per agent) plus the workspace's .farai/agents. */
    val agents = dev.harness.core.agents.AgentStore(prompts) { workspace.root }
    /** Rules from prompts/permissions.md, re-read on every check so edits apply at once. */
    val permissions = dev.harness.core.permission.PermissionEngine {
        prompts.readOrNull("permissions.md")?.let { dev.harness.core.permission.PermissionRules.parse(it, "permissions.md") }.orEmpty()
    }
    val jobs = dev.harness.tools.JobManager { File(workspace.root, ".farai/jobs") }.also { it.onChange = { syncService() } }

    /**
     * Keeps the foreground service up while an agent runs or a background job (a localhost
     * server, a watcher) is alive, so Android doesn't kill them when the app is in the background.
     */
    fun syncService() {
        val runs = runCatching { runner.activeCount }.getOrDefault(0)
        val live = runCatching { jobs.allRunning() }.getOrDefault(emptyList())
        dev.harness.core.run.AgentService.sync(context, runs, runTitle, live.size, live.firstOrNull()?.command)
    }
    val fileHistory = dev.harness.core.FileHistory(File(context.filesDir, "file-history"))
    val goals = dev.harness.core.GoalStore(File(context.filesDir, "goals"))

    /** MCP servers from prompts/mcp.md and the workspace's .mcp.json / farai.json / .farai/mcp.json. */
    val mcp: dev.harness.core.mcp.McpManager by lazy {
        dev.harness.core.mcp.McpManager(
            scope = appScope,
            configs = { dev.harness.core.mcp.McpConfig.load(prompts.readOrNull("mcp.md"), workspace.root) },
            linux = { linux },
            useLinux = { settings.currentOrNull()?.agent?.useLinux != false },
            workspaceRoot = workspace.root,
            http = http,
            auth = dev.harness.core.mcp.McpAuth(File(context.filesDir, "mcp-auth"), http, ::openInBrowser),
            openUrl = ::openInBrowser,
            sampler = { req -> sampleWithModel(req) },
        )
    }

    /** Opens a web page outside the app (OAuth sign-in, pages MCP servers ask the user to visit). */
    fun openInBrowser(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** MCP sampling: runs a server's prompt on the model configured in Settings → Provider. */
    private suspend fun sampleWithModel(req: dev.harness.core.mcp.SamplingRequest): dev.harness.core.mcp.SamplingResponse {
        val p = settings.current().provider
        val messages = buildList {
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let { add(dev.harness.net.ChatMessage(dev.harness.net.Role.SYSTEM, it)) }
            req.messages.forEach { (role, text) ->
                add(dev.harness.net.ChatMessage(if (role == "assistant") dev.harness.net.Role.ASSISTANT else dev.harness.net.Role.USER, text))
            }
        }
        val cap = p.maxTokens.takeIf { it > 0 }
        val maxTokens = listOfNotNull(req.maxTokens, cap).minOrNull()
        val result = client.chat(dev.harness.net.ChatRequest(model = p.model, messages = messages, temperature = req.temperature, maxTokens = maxTokens, stream = false))
        var text = result.content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        var stop = if (result.finishReason == "length" || result.finishReason == "max_tokens") "maxTokens" else "endTurn"
        req.stopSequences.forEach { seq -> val i = text.indexOf(seq); if (seq.isNotEmpty() && i >= 0) { text = text.substring(0, i); stop = "stopSequence" } }
        return dev.harness.core.mcp.SamplingResponse(text, p.model, stop)
    }

    /**
     * Long-term memory: Markdown files in filesDir/memory (agent scope) and the workspace's
     * .farai/memory (project scope), plus recall over every past session. See core/memory.
     */
    val memoryStore = dev.harness.core.memory.MemoryStore(
        root = File(context.filesDir, "memory"),
        projectRootProvider = { File(workspace.root, ".farai/memory") },
        seed = { dev.harness.core.memory.MemorySeed.fromAssets(context.assets) },
    )
    val memory: dev.harness.core.memory.MemoryRuntime by lazy {
        dev.harness.core.memory.MemoryRuntime(
            store = memoryStore,
            recall = dev.harness.core.memory.RecallStore(File(context.filesDir, "memory/.recall")) {
                sessions.all().map { e -> dev.harness.core.memory.RecallStore.RecallSession(e.id, e.title, e.updatedAt, sessions.decodeMessages(e.messagesJson)) }
            },
            prompts = prompts,
            client = client,
            settings = { settings.current() },
            scope = appScope,
            transcript = { compactor.transcript(it) },
            reflectionTools = { tools.schemas(persona) { it.name == "memory" || it.name == "recall" } },
        )
    }

    val engine = Engine(
        client = client,
        persona = persona,
        tools = tools,
        compactor = compactor,
        repoMapper = repoMapper,
        workspace = workspace,
        http = http,
        settings = { settings.current() },
        environment = ::environment,
        androidContext = context.applicationContext,
        agents = agents,
        permissions = permissions,
        jobs = jobs,
        fileHistory = fileHistory,
        goals = goals,
        mcp = mcp,
        memory = memory,
        sessionTitle = { id -> runCatching { sessions.get(id)?.title }.getOrNull().orEmpty() },
    )

    fun environment(model: String): EnvironmentInfo {
        val now = Date()
        return EnvironmentInfo(
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = Build.VERSION.RELEASE ?: "?",
            apiLevel = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?",
            device = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" "),
            cwd = workspace.root.absolutePath,
            model = model,
            dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.US).format(now),
            timeZone = TimeZone.getDefault().id,
            shell = if (linux.isReady && settings.currentOrNull()?.agent?.useLinux != false) {
                "${linux.label} via PRoot (${linux.active.value.packageManager} package manager" +
                    (if (linux.active.value == dev.harness.core.linux.Distro.UBUNTU) ", glibc" else ", musl") +
                    "; /workspace = workspace, /sdcard = shared storage, fake root)"
            } else "Android /system/bin/sh (toybox; no package manager)",
            sharedStorage = StorageAccess.rootIfGranted(context)?.let { "${it.absolutePath} (also /sdcard)" },
        )
    }

    init {
        appScope.launch(Dispatchers.IO) { runCatching { skills.seed(dev.harness.core.skills.BundledSkills.all) } }
    }
}
