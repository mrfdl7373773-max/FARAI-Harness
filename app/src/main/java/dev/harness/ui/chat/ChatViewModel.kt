package dev.harness.ui.chat

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.harness.AppContainer
import dev.harness.core.ChatText
import dev.harness.core.Goal
import dev.harness.core.Question
import dev.harness.core.agents.AgentDef
import dev.harness.core.permission.Approval
import dev.harness.core.permission.ApprovalRequest
import dev.harness.core.run.InteractiveGate
import dev.harness.core.run.PendingQuestion
import dev.harness.core.ConfirmationGate
import dev.harness.core.EngineEvent
import dev.harness.core.PersonaLoader
import dev.harness.core.PromptRepository
import dev.harness.core.SessionManager
import dev.harness.core.SessionState
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.LinuxControl
import dev.harness.core.commands.CommandRegistry
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.NavTarget
import dev.harness.core.commands.SessionSummary
import dev.harness.data.session.SessionEntity
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatMessage
import dev.harness.net.Role
import dev.harness.tools.TodoItem
import dev.harness.tools.ToolRegistry
import dev.harness.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import dev.harness.core.run.AgentRunner
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class PendingConfirmation(
    val title: String,
    val detail: String,
    val answer: CompletableDeferred<Approval>,
    val canRemember: Boolean = false,
)

data class ChatUiState(
    val loading: Boolean = true,
    val title: String = SessionManager.DEFAULT_TITLE,
    val model: String = "",
    /** Active primary agent and the ones the user can switch to. */
    val agent: String = "build",
    val agents: List<AgentDef> = emptyList(),
    val goal: Goal? = null,
    val question: PendingQuestion? = null,
    val items: List<ChatItem> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val waitingFirstToken: Boolean = false,
    val confirmation: PendingConfirmation? = null,
    val providerConfigured: Boolean = true,
)

sealed interface ChatEvent {
    data class Navigate(val target: NavTarget) : ChatEvent
    data class Share(val title: String, val text: String) : ChatEvent
}

class ChatViewModel(private val container: AppContainer, private val sessionId: String) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var state: SessionState? = null
    private val stateReady = MutableStateFlow<SessionState?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val todos: StateFlow<List<TodoItem>> = stateReady.flatMapLatest { it?.todos?.items ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null
    private val keys = AtomicLong(0)
    private fun key(prefix: String) = "$prefix-${keys.incrementAndGet()}"

    val registry: CommandRegistry get() = container.commands

    init {
        viewModelScope.launch {
            val live = container.runner.live(sessionId)
            val s = live?.state ?: container.sessions.load(sessionId) ?: run {
                val created = container.sessions.create(container.settings.current().provider.model)
                container.sessions.toState(created)
            }
            state = s
            stateReady.value = s
            val entity = container.sessions.get(s.id)
            val cfg = container.settings.current()
            _ui.update {
                it.copy(
                    loading = false,
                    title = entity?.title ?: SessionManager.DEFAULT_TITLE,
                    model = cfg.provider.model,
                    agent = s.agent,
                    agents = primaryAgents(),
                    goal = container.goals.get(s.id),
                    items = buildItems(s.messages.toList()),
                    providerConfigured = cfg.provider.isConfigured,
                )
            }
            if (live != null) attach(live)
        }
        // Background memory reflection: a small note when it changed something in this session.
        viewModelScope.launch {
            container.memory.reports.collect { r ->
                val id = state?.id ?: sessionId
                if (r.sessionId != id) return@collect
                if (r.failed) notice(r.summary, error = true)
                else if (r.changes > 0) notice("🧠 " + trf("Memory updated ({0})", r.changes.toString()) + ": " + r.summary.lineSequence().firstOrNull().orEmpty().take(160))
            }
        }
        viewModelScope.launch { settings.collect { cfg -> _ui.update { it.copy(model = cfg.provider.model, providerConfigured = cfg.provider.isConfigured) } } }
        // Agent files can be edited in Settings → Prompts; refresh the picker when they change.
        viewModelScope.launch { container.prompts.version.collect { _ui.update { it.copy(agents = primaryAgents()) } } }
    }

    // ---- Sending -------------------------------------------------------------------------

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _ui.value.running) return
        val s = state ?: return
        if (registry.isCommand(text)) {
            viewModelScope.launch { handleCommand(text) }
            return
        }
        // A leading "//" sends a literal message that starts with "/".
        val payload = if (text.startsWith("//")) text.substring(1) else text
        _ui.update { it.copy(items = it.items + ChatItem.User(key("u"), payload)) }
        runAgent(s) { g, onEvent -> container.engine.run(s, payload, g, onEvent) }
        maybeGenerateTitle(s, payload)
    }

    private fun runAgent(s: SessionState, block: suspend (ConfirmationGate, (EngineEvent) -> Unit) -> Unit) {
        if (container.runner.isRunning(s.id)) return
        val run = container.runner.start(s, _ui.value.title.takeIf { it != SessionManager.DEFAULT_TITLE }) { gate, emit -> block(gate, emit) }
        attach(run)
    }

    private var attachedRun: AgentRunner.Run? = null
    private var attachJob: Job? = null

    /** Follows a run that lives in the app scope; it keeps going if this screen goes away. */
    private fun attach(run: AgentRunner.Run) {
        attachJob?.cancel()
        attachedRun?.let { it.attached-- }
        attachedRun = run
        run.attached++
        _ui.update { it.copy(running = true, waitingFirstToken = true, streaming = "") }
        attachJob = viewModelScope.launch {
            launch { run.events.collect { onEvent(it) } }
            launch {
                run.approval.collect { a ->
                    _ui.update { it.copy(confirmation = a?.let { p -> PendingConfirmation(p.title, p.detail, p.answer, p.canRemember) }) }
                }
            }
            launch { run.question.collect { q -> _ui.update { it.copy(question = q) } } }
            run.running.first { !it }
            flushStreaming()
            _ui.update { it.copy(running = false, waitingFirstToken = false, confirmation = null, question = null) }
            val e = container.sessions.get(run.state.id)
            _ui.update { ui -> ui.copy(title = e?.title ?: ui.title, agent = run.state.agent, goal = container.goals.get(run.state.id), items = mergeNotices(buildItems(run.state.messages.toList()), ui.items)) }
            maybeGenerateTitle(run.state)
            run.attached--
            if (attachedRun === run) attachedRun = null
            coroutineContext.cancelChildren()
        }
    }

    /**
     * Replaces the newest user message with [newText] and runs the turn again: stops a run in
     * flight, rolls back what that turn changed (messages and files), then sends the new text.
     */
    fun editLast(newText: String) {
        val text = newText.trim()
        val s = state ?: return
        if (text.isEmpty()) return
        viewModelScope.launch {
            if (_ui.value.running) {
                stop()
                container.runner.live(s.id)?.running?.first { !it }
                attachJob?.join()
            }
            val lastUser = s.messages.indexOfLast { it.role == Role.USER && !ChatText.isSynthetic(it.content) }
            if (lastUser >= 0) {
                val checkpoint = s.messages.take(lastUser + 1).count { it.role == Role.USER && !ChatText.isSynthetic(it.content) }
                runCatching { container.fileHistory.restoreFrom(s.id, checkpoint) }
                while (s.messages.size > lastUser) s.messages.removeAt(s.messages.size - 1)
                s.finished = false
            }
            _ui.update { it.copy(items = buildItems(s.messages), running = false, streaming = "") }
            persist(dev.harness.core.SessionStatus.ACTIVE)
            send(text)
        }
    }

    fun stop() {
        _ui.value.confirmation?.answer?.complete(Approval.Deny())
        _ui.value.question?.answer?.complete(null)
        state?.let { container.runner.stop(it.id) }
    }

    /** Used for /run, outside a background run. */
    private val gate = object : InteractiveGate {
        override suspend fun confirm(title: String, detail: String): Boolean = decide(ApprovalRequest(title, detail)) !is Approval.Deny
        override suspend fun decide(request: ApprovalRequest): Approval {
            val d = CompletableDeferred<Approval>()
            _ui.update { it.copy(confirmation = PendingConfirmation(request.title, request.detail, d, request.always.isNotEmpty())) }
            return try { d.await() } finally { _ui.update { it.copy(confirmation = null) } }
        }
        override suspend fun ask(questions: List<Question>): List<List<String>>? {
            val d = CompletableDeferred<List<List<String>>?>()
            _ui.update { it.copy(question = PendingQuestion(questions, d)) }
            return try { d.await() } finally { _ui.update { it.copy(question = null) } }
        }
    }

    fun answerConfirmation(answer: Approval) { _ui.value.confirmation?.answer?.complete(answer) }

    fun answerQuestion(answers: List<List<String>>?) { _ui.value.question?.answer?.complete(answers) }

    private fun primaryAgents(): List<AgentDef> = runCatching { container.agents.primary() }.getOrDefault(emptyList())

    /**
     * Replaces the "first line of the message" title with a model-written one. Runs in the app
     * scope as soon as the first message is sent (so leaving the screen doesn't lose it), and
     * again when a run ends in case the first try failed.
     */
    private fun maybeGenerateTitle(s: SessionState, sent: String? = null) {
        if (titled.contains(s.id) || !titling.add(s.id)) return
        container.appScope.launch {
            try {
                if (!container.settings.current().agent.autoTitle) return@launch
                val msgs = runCatching { s.messages.toList() }.getOrDefault(emptyList())
                val firstUser = msgs.firstOrNull { it.role == Role.USER && !ChatText.isSynthetic(it.content) }?.content
                    ?: sent?.takeIf { msgs.none { m -> m.role == Role.USER && !ChatText.isSynthetic(m.content) } }
                    ?: return@launch
                val auto = container.sessions.autoTitle(msgs) ?: container.sessions.autoTitle(listOf(dev.harness.net.ChatMessage(Role.USER, firstUser)))
                val before = container.sessions.get(s.id)?.title
                if (before != null && before != auto && before != SessionManager.DEFAULT_TITLE) { titled += s.id; return@launch }
                val t = container.engine.generateTitle(ChatText.stripReminders(firstUser)) ?: return@launch
                // Save first so the title exists even if the run saves after us.
                container.runner.live(s.id)?.title = t
                var tries = 0
                while (tries++ < 20 && container.sessions.get(s.id) == null) kotlinx.coroutines.delay(500)
                val now = container.sessions.get(s.id)?.title
                if (now != null && now != auto && now != SessionManager.DEFAULT_TITLE) { titled += s.id; return@launch }
                container.sessions.rename(s.id, t)
                titled += s.id
                _ui.update { it.copy(title = t) }
            } finally {
                titling.remove(s.id)
            }
        }
    }

    private val titled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val titling = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun onEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.TurnStarted -> Unit
            is EngineEvent.AssistantDelta -> _ui.update { it.copy(streaming = it.streaming + e.text, waitingFirstToken = false) }
            is EngineEvent.AssistantMessage -> _ui.update {
                val text = e.message.content.orEmpty()
                it.copy(
                    streaming = "",
                    waitingFirstToken = false,
                    items = if (text.isNotBlank()) it.items + ChatItem.Assistant(key("a"), text) else it.items,
                )
            }
            is EngineEvent.ToolStarted -> _ui.update { ui ->
                val card = ChatItem.Tool(key("t"), e.callId, e.name, e.arguments)
                if (e.parentCallId == null) ui.copy(items = ui.items + card, waitingFirstToken = false)
                else ui.copy(items = ui.items.map { if (it is ChatItem.Tool && it.callId == e.parentCallId) it.copy(children = it.children + card, liveText = "") else it })
            }
            is EngineEvent.ToolFinished -> _ui.update { ui ->
                fun ChatItem.Tool.done() = copy(result = e.result.output, isError = e.result.isError, running = false)
                ui.copy(
                    items = ui.items.map { item ->
                        if (item !is ChatItem.Tool) item
                        else if (e.parentCallId == null && item.callId == e.callId) item.done()
                        else if (e.parentCallId != null && item.callId == e.parentCallId)
                            item.copy(children = item.children.map { ch -> if (ch.callId == e.callId) ch.done() else ch })
                        else item
                    },
                    waitingFirstToken = e.parentCallId == null,
                )
            }
            is EngineEvent.SubagentDelta -> _ui.update { ui ->
                ui.copy(items = ui.items.map { if (it is ChatItem.Tool && it.callId == e.parentCallId) it.copy(liveText = (it.liveText + e.text).takeLast(600)) else it })
            }
            is EngineEvent.Compacted -> _ui.update { it.copy(items = it.items + ChatItem.Compacted(key("c"), e.summary, e.removedCount)) }
            is EngineEvent.Finished -> _ui.update { it.copy(items = it.items + ChatItem.Finished(key("f"), e.summary)) }
            is EngineEvent.TurnLimitReached -> notice(trf("Stopped after {0} steps (the step limit). Send a message to continue, or raise it with `/turns`.", e.turns), true)
            is EngineEvent.Failed -> notice(e.message, true)
            is EngineEvent.Notice -> notice(e.text, e.error)
            is EngineEvent.GoalChanged -> _ui.update { it.copy(goal = e.goal) }
        }
    }

    private fun flushStreaming() {
        _ui.update { if (it.streaming.isNotBlank()) it.copy(items = it.items + ChatItem.Assistant(key("a"), it.streaming), streaming = "") else it.copy(streaming = "") }
    }

    private fun notice(text: String, error: Boolean = false) {
        _ui.update { it.copy(items = it.items + ChatItem.Notice(key("n"), text, error)) }
    }

    private suspend fun persist(status: String? = null) {
        val s = state ?: return
        val e = container.sessions.save(s, container.settings.current().provider.model, status = status)
        _ui.update { it.copy(title = e.title, agent = s.agent) }
    }

    // ---- Commands ------------------------------------------------------------------------

    private suspend fun handleCommand(text: String) {
        val ctx = commandContext ?: return
        when (val r = registry.dispatch(text, ctx)) {
            is CommandResult.Output -> notice(r.text)
            is CommandResult.Error -> notice(r.text, true)
            is CommandResult.Navigate -> { r.note?.let { notice(it) }; _events.send(ChatEvent.Navigate(r.target)) }
            is CommandResult.Share -> _events.send(ChatEvent.Share(r.title, r.text))
            is CommandResult.SendToAgent -> {
                r.note?.let { notice(it) }
                val s = state ?: return
                _ui.update { it.copy(items = it.items + ChatItem.User(key("u"), r.text)) }
                runAgent(s) { g, onEvent -> container.engine.run(s, r.text, g, onEvent) }
            }
            CommandResult.None -> Unit
        }
        _ui.update { it.copy(agent = state?.agent ?: it.agent, goal = state?.let { st -> container.goals.get(st.id) }) }
    }

    fun suggestions(input: String): List<CommandRegistry.Suggestion> = registry.suggestions(input)

    fun setAgent(name: String) {
        if (_ui.value.running) return
        state?.agent = name
        _ui.update { it.copy(agent = name) }
        viewModelScope.launch { persist() }
    }

    fun saveNow() { viewModelScope.launch { persist() } }

    fun exportTranscript() {
        viewModelScope.launch { _events.send(ChatEvent.Share(_ui.value.title, transcriptText())) }
    }

    private suspend fun transcriptText(): String {
        val s = state ?: return ""
        val e = container.sessions.save(s, container.settings.current().provider.model)
        return container.sessions.transcript(e, s.messages)
    }

    private val commandContext: CommandContext? get() {
        val s = state ?: return null
        return object : CommandContext {
            override val registry: CommandRegistry = container.commands
            override val prompts: PromptRepository = container.prompts
            override val persona: PersonaLoader = container.persona
            override val tools: ToolRegistry = container.tools
            override val state: SessionState = s
            override val linux: LinuxControl = container.linuxControl
            override val workspaceRoot: java.io.File = container.workspace.root
            override val sharedStorage: java.io.File? = dev.harness.core.linux.StorageAccess.rootIfGranted(container.context)
            override val agents = container.agents
            override val goals = container.goals
            override val jobs = container.jobs
            override val mcp = container.mcp
            override val memory = container.memory
            override suspend fun setAgent(name: String) { this@ChatViewModel.setAgent(name) }
            override fun goalChanged(goal: Goal?) { _ui.update { it.copy(goal = goal) } }
            override fun lastUndoRestored(): List<String> = lastRestored

            override suspend fun settings(): AppSettings = container.settings.current()
            override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)
            override suspend fun sessionTitle(): String = _ui.value.title
            override suspend fun renameSession(title: String) {
                persist(); container.sessions.rename(s.id, title); _ui.update { it.copy(title = title) }
            }
            override suspend fun saveSession() = persist()
            override suspend fun forkSession(upToMessages: Int?): String? = container.sessions.fork(s.id, upToMessages)?.id
            override suspend fun sessionTags(): List<String> =
                container.sessions.get(s.id)?.tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            override suspend fun setSessionTags(tags: List<String>) { persist(); container.sessions.setTags(s.id, tags) }
            override suspend fun searchSessions(query: String) = container.sessions.search(query).map { it.summary() }
            override suspend fun sessionsByTag(tag: String) = container.sessions.byTag(tag).map { it.summary() }
            override suspend fun transcript(): String = transcriptText()
            override suspend fun clearContext() {
                s.messages.clear(); s.finished = false
                _ui.update { it.copy(items = emptyList()) }
                persist()
            }
            override suspend fun resetSession() {
                s.messages.clear(); s.todos.clear(); s.agent = dev.harness.core.agents.AgentStore.DEFAULT; s.disabledTools.clear(); s.finished = false
                s.remembered.clear(); s.subSessions.clear(); container.goals.set(s.id, null)
                _ui.update { it.copy(items = emptyList(), agent = s.agent, goal = null) }
                persist(dev.harness.core.SessionStatus.ACTIVE)
            }
            override suspend fun systemMessagePreview(): String = container.engine.previewSystemMessage(s)
            override fun estimateTokens(): Int = container.engine.estimateTokens(s)
            override suspend fun compactNow(): String? {
                if (s.lastSystemMessage.isEmpty()) s.lastSystemMessage = container.engine.previewSystemMessage(s)
                val r = container.engine.compactNow(s) ?: return null
                _ui.update { it.copy(items = buildItems(s.messages)) }
                persist()
                return r.summary
            }
            override suspend fun listModels(): List<String> = container.client.listModels()
            override suspend fun healthCheck(): String = this@ChatViewModel.healthCheck()
            override suspend fun runTool(name: String, argumentsJson: String): ToolResult {
                val r = container.engine.runToolDirect(s, name, argumentsJson, gate) { }
                persist()
                return r
            }
            override suspend fun retryLast(): Boolean {
                val lastUser = s.messages.indexOfLast { it.role == Role.USER }
                if (lastUser < 0) return false
                while (s.messages.size > lastUser + 1) s.messages.removeAt(s.messages.size - 1)
                _ui.update { it.copy(items = buildItems(s.messages)) }
                runAgent(s) { g, onEvent -> container.engine.resume(s, g, onEvent) }
                return true
            }
            override suspend fun undoLast(): Int {
                val lastUser = s.messages.indexOfLast { it.role == Role.USER && !ChatText.isSynthetic(it.content) }
                if (lastUser < 0) return 0
                val checkpoint = s.messages.take(lastUser + 1).count { it.role == Role.USER && !ChatText.isSynthetic(it.content) }
                lastRestored = runCatching { container.fileHistory.restoreFrom(s.id, checkpoint) }.getOrDefault(emptyList())
                    .map { container.workspace.relative(java.io.File(it)) }
                val n = s.messages.size - lastUser
                while (s.messages.size > lastUser) s.messages.removeAt(s.messages.size - 1)
                s.finished = false
                _ui.update { it.copy(items = buildItems(s.messages)) }
                persist(dev.harness.core.SessionStatus.ACTIVE)
                return n
            }
        }
    }

    suspend fun healthCheck(): String {
        val cfg = container.settings.current().provider
        val started = System.nanoTime()
        return try {
            val models = container.client.listModels()
            val ms = (System.nanoTime() - started) / 1_000_000
            val has = cfg.model in models
            "✅ **Provider reachable** · ${ms} ms\nEndpoint: `${cfg.baseUrl.trimEnd('/')}/models`\n${models.size} models listed\n" +
                if (models.isEmpty()) "" else if (has) trf("Model `{0}` is available.", cfg.model) else trf("⚠️ Model `{0}` is not in the list (some providers hide models; it may still work).", cfg.model)
        } catch (e: Exception) {
            val ms = (System.nanoTime() - started) / 1_000_000
            "❌ **Provider check failed** after ${ms} ms\n`${e.message ?: e::class.java.simpleName}`\n\nCheck Base URL and API key with `/config`."
        }
    }

    // ---- Item building -------------------------------------------------------------------

    private fun SessionEntity.summary() = SessionSummary(id, title, updatedAt, messageCount, tags.split(',').filter { it.isNotBlank() }, model)

    private var lastRestored: List<String> = emptyList()

    private fun buildItems(messages: List<ChatMessage>): List<ChatItem> {
        val out = mutableListOf<ChatItem>()
        val results = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
        val produced = runCatching { dev.harness.core.files.ProducedFiles.byTurn(messages, container.workspace) }.getOrDefault(emptyMap())
        for ((i, m) in messages.withIndex()) {
            when (m.role) {
                Role.USER -> {
                    val text = m.content.orEmpty()
                    when {
                        ChatText.isCheckpoint(text) -> out += ChatItem.Compacted(key("c"), text.substringAfter(ChatText.CHECKPOINT_OPEN).substringBefore("</conversation-checkpoint>").trim(), 0)
                        ChatText.isSynthetic(text) -> Unit // harness reminders (goal rounds, job notices) stay out of the chat
                        else -> out += ChatItem.User(key("u"), ChatText.stripReminders(text))
                    }
                }
                Role.ASSISTANT -> {
                    m.content?.takeIf { it.isNotBlank() }?.let { out += ChatItem.Assistant(key("a"), it) }
                    m.toolCalls?.forEach { tc ->
                        val r = results[tc.id]
                        val isErr = r?.content?.startsWith("ERROR") == true
                        out += ChatItem.Tool(key("t"), tc.id, tc.function.name, tc.function.arguments, r?.content, isErr, running = false)
                        if (tc.function.name == "finish" && r != null && !isErr) out += ChatItem.Finished(key("f"), r.content.orEmpty())
                    }
                }
                else -> Unit
            }
            produced[i]?.let { out += ChatItem.Files(key("files"), it) }
        }
        return out
    }

    /** Keeps the live view (with nested subagent cards and notices) when it matches the rebuilt history. */
    private fun mergeNotices(rebuilt: List<ChatItem>, live: List<ChatItem>): List<ChatItem> {
        val liveCore = live.filter { it !is ChatItem.Notice && it !is ChatItem.Compacted }
        val sameShape = liveCore.size == rebuilt.size && liveCore.zip(rebuilt).all { (a, b) -> a::class == b::class }
        return if (sameShape) live else rebuilt + live.filterIsInstance<ChatItem.Notice>().takeLast(1)
    }

    override fun onCleared() {
        // The run itself keeps going in the background; only stop following it.
        attachedRun?.let { it.attached-- }
        attachedRun = null
        super.onCleared()
    }
}
