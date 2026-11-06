package dev.harness.core

import android.content.Context
import dev.harness.core.agents.AgentDef
import dev.harness.core.agents.AgentKind
import dev.harness.core.agents.AgentStore
import dev.harness.core.agents.ToolSelector
import dev.harness.core.permission.Approval
import dev.harness.core.permission.ApprovalRequest
import dev.harness.core.permission.PermEffect
import dev.harness.core.permission.PermissionEngine
import dev.harness.core.permission.Wildcard
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatClient
import dev.harness.net.ChatMessage
import dev.harness.net.ChatRequest
import dev.harness.net.ChatResult
import dev.harness.net.HarnessJson
import dev.harness.net.ProviderException
import dev.harness.net.RetryInfo
import dev.harness.net.RetryKind
import dev.harness.net.Role
import dev.harness.net.ToolCall
import dev.harness.net.ToolSchema
import dev.harness.net.Usage
import dev.harness.tools.JobManager
import dev.harness.tools.PermissionAsker
import dev.harness.tools.SubagentResult
import dev.harness.tools.SubagentRunner
import dev.harness.tools.TodoStore
import dev.harness.tools.ToolArgumentException
import dev.harness.tools.ToolContext
import dev.harness.tools.ToolRegistry
import dev.harness.tools.ToolResult
import dev.harness.tools.ToolSession
import dev.harness.tools.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Everything the UI needs to render a run as it happens. */
sealed interface EngineEvent {
    data class TurnStarted(val turn: Int) : EngineEvent
    data class AssistantDelta(val text: String) : EngineEvent
    data class AssistantMessage(val message: ChatMessage) : EngineEvent
    data class ToolStarted(val callId: String, val name: String, val arguments: String, val parentCallId: String?) : EngineEvent
    data class ToolFinished(val callId: String, val name: String, val result: ToolResult, val parentCallId: String?) : EngineEvent
    data class SubagentDelta(val parentCallId: String, val text: String) : EngineEvent
    data class Compacted(val summary: String, val removedCount: Int) : EngineEvent
    data class Finished(val summary: String) : EngineEvent
    data class TurnLimitReached(val turns: Int) : EngineEvent
    data class Failed(val message: String) : EngineEvent
    /** Something the user should see that is not a model message: a retry, a job result, a goal check. */
    data class Notice(val text: String, val error: Boolean = false) : EngineEvent
    data class GoalChanged(val goal: Goal?) : EngineEvent
}

enum class RunOutcome { REPLIED, FINISHED, TURN_LIMIT, FAILED }

/** A subagent conversation kept so the parent can send it follow-ups (task_id). */
class SubSession(val id: String, val agent: String, val state: SessionState)

/** Mutable state of one chat session, owned by the ViewModel and persisted by SessionManager. */
class SessionState(
    val id: String,
    messages: List<ChatMessage> = emptyList(),
    val todos: TodoStore = TodoStore(),
    /** Name of the active primary agent (prompts/agents/<name>.md). */
    var agent: String = AgentStore.DEFAULT,
    val disabledTools: MutableSet<String> = mutableSetOf(),
) {
    val messages: MutableList<ChatMessage> = messages.toMutableList()
    val tools = ToolSession()
    /** "Always allow" answers for this session: action + pattern keys. */
    val remembered: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val subSessions: MutableMap<String, SubSession> = ConcurrentHashMap()
    /** Agent the model was last told it is (for switch reminders). */
    @Volatile var promptedAgent: String? = null
    /** Number of user messages when the current run started; keys file snapshots for /undo. */
    @Volatile var checkpoint: Int = 0
    @Volatile var lastSystemMessage: String = ""
    @Volatile var lastRequestJson: String = ""
    @Volatile var lastResponseHeaders: Map<String, String> = emptyMap()
    @Volatile var lastUsage: Usage? = null
    @Volatile var lastToolName: String? = null
    @Volatile var lastToolResult: String? = null
    @Volatile var finished: Boolean = false
    /** Memory compiled at the start of the current user message (stable for the whole run). */
    @Volatile var memorySnapshot: String? = null
    @Volatile var memoryForSubagents: String? = null

    fun userMessageCount(): Int = messages.count { it.role == Role.USER && !ChatText.isSynthetic(it.content) }
}

/** Helpers for text the harness injects into the conversation. */
object ChatText {
    const val REMINDER_OPEN = "<system-reminder>"
    const val REMINDER_CLOSE = "</system-reminder>"
    const val CHECKPOINT_OPEN = "<conversation-checkpoint>"

    fun reminder(text: String) = "$REMINDER_OPEN\n${text.trim()}\n$REMINDER_CLOSE"

    /** A user message written by the harness, not typed by the user. */
    fun isSynthetic(content: String?): Boolean {
        val t = content?.trimStart() ?: return false
        return (t.startsWith(REMINDER_OPEN) && stripReminders(t).isBlank()) || t.startsWith(CHECKPOINT_OPEN) || t.startsWith("## Session summary")
    }

    fun isCheckpoint(content: String?): Boolean = content?.trimStart()?.let { it.startsWith(CHECKPOINT_OPEN) || it.startsWith("## Session summary") } == true

    /** What the user actually typed, with injected reminders removed. */
    fun stripReminders(text: String): String =
        text.replace(Regex("(?s)<system-reminder>.*?</system-reminder>"), "").trim()

    fun reminderBodies(text: String): List<String> =
        Regex("(?s)<system-reminder>(.*?)</system-reminder>").findAll(text).map { it.groupValues[1].trim() }.toList()
}

/**
 * The agent loop. Builds the system message from prompt files and the active agent, calls the
 * model, runs tool calls (side by side when they are read-only), feeds results back, and repeats
 * until the model answers in text, calls finish, or reaches its step limit. On top of that:
 * permission rules with "always allow", doom-loop detection, argument repair, context pruning and
 * compaction (also when the provider says the context is too long), background job notices, a
 * final summary when steps run out, and `/goal` rounds checked by a reviewer call.
 */
class Engine(
    private val client: ChatClient,
    private val persona: PersonaLoader,
    private val tools: ToolRegistry,
    private val compactor: ContextCompactor,
    private val repoMapper: RepoMapper,
    private val workspace: Workspace,
    private val http: OkHttpClient,
    private val settings: suspend () -> AppSettings,
    private val environment: (model: String) -> EnvironmentInfo,
    private val androidContext: Context? = null,
    val agents: AgentStore? = null,
    private val permissions: PermissionEngine = PermissionEngine { emptyList() },
    private val jobs: JobManager? = null,
    private val fileHistory: FileHistory? = null,
    val goals: GoalStore? = null,
    val mcp: dev.harness.core.mcp.McpManager? = null,
    /** Long-term memory: prompt section, memory/recall tools, nudges, reflection. */
    val memory: dev.harness.core.memory.MemoryRuntime? = null,
    /** Session title for memory reports and recall archives. */
    private val sessionTitle: suspend (String) -> String = { "" },
) {
    private val subIds = AtomicInteger(0)

    fun agentFor(state: SessionState): AgentDef = agents?.resolvePrimary(state.agent) ?: legacyAgent(state.agent)

    /** Without an AgentStore (tests), map the three classic names onto built-in definitions. */
    private fun legacyAgent(name: String): AgentDef = when (name) {
        "verify" -> AgentStore.FALLBACK.copy(name = "verify", verify = true)
        "plan" -> AgentStore.FALLBACK.copy(name = "plan")
        else -> AgentStore.FALLBACK
    }

    /** Names of tools that would be offered to the main agent for [state]. */
    suspend fun activeToolNames(state: SessionState): List<String> {
        val s = settings()
        val agent = agentFor(state)
        return tools.all.filter { isOffered(it.name, state, s, agent) }.map { it.name }
    }

    private fun isOffered(name: String, state: SessionState, s: AppSettings, agent: AgentDef): Boolean =
        name !in state.disabledTools && agent.tools.allows(name) && !(name == "task" && !s.agent.subagents) &&
            !((name == "memory" || name == "recall") && (!s.agent.memory || memory == null))

    /** Builds the exact system message the next request would send (used by /context). */
    suspend fun previewSystemMessage(state: SessionState, nextInput: String = ""): String {
        val s = settings()
        return persona.buildSystemMessage(systemContext(state, s, nextInput, agentFor(state)))
    }

    private suspend fun systemContext(state: SessionState, s: AppSettings, input: String, agent: AgentDef): SystemContext = SystemContext(
        env = environment(agent.model ?: s.provider.model),
        agent = agent,
        includePlanning = agent.name != "plan" && s.agent.autoPlanning && PersonaLoader.looksComplex(input),
        todos = state.todos.items.value,
        repoMap = if (s.agent.repoMap) repoMapper.render(workspace.root) else null,
        toolNames = tools.all.filter { isOffered(it.name, state, s, agent) }.map { it.name },
        instructions = projectInstructions(),
        goal = goals?.get(state.id),
        mcpInstructions = mcp?.instructions().orEmpty(),
        memory = state.memorySnapshot,
    )

    /** The workspace's own AGENTS.md (or CLAUDE.md) and .farai/AGENTS.md. */
    fun projectInstructions(): List<Pair<String, String>> {
        val root = workspace.root
        val out = ArrayList<Pair<String, String>>()
        (listOf("AGENTS.md", "CLAUDE.md").map { File(root, it) }.firstOrNull { it.isFile })?.let { out += it.name to it.readText().take(MAX_INSTRUCTIONS) }
        File(root, ".farai/AGENTS.md").takeIf { it.isFile }?.let { out += ".farai/AGENTS.md" to it.readText().take(MAX_INSTRUCTIONS) }
        return out
    }

    /**
     * Appends [userInput] and runs the loop. Events are delivered through [onEvent] (possibly from
     * several coroutines when tools run in parallel; delivery is serialized).
     * Cancellation (Stop button) propagates as CancellationException after state is consistent.
     */
    suspend fun run(
        state: SessionState,
        userInput: String,
        confirm: ConfirmationGate,
        onEvent: (EngineEvent) -> Unit,
    ): RunOutcome {
        state.finished = false
        val agent = agentFor(state)
        refreshMemory(state)
        val nudge = memory?.let { m -> runCatching { m.nudgeFor(userInput) }.getOrNull() }
        val head = Clock.nowLine() + (nudge?.let { "\n\n$it" } ?: "")
        state.messages += ChatMessage(Role.USER, ChatText.reminder(head) + "\n\n" + withSwitchReminder(state, agent, userInput))
        state.checkpoint = state.userMessageCount()
        val outcome = runWithGoal(state, userInput, confirm, serialize(onEvent))
        afterRunMemory(state)
        return outcome
    }

    /** Compiles memory into the session once per user message (edits show up from the next one). */
    suspend fun refreshMemory(state: SessionState) {
        val m = memory ?: run { state.memorySnapshot = null; state.memoryForSubagents = null; return }
        state.memorySnapshot = runCatching { m.compile() }.getOrNull()
        state.memoryForSubagents = runCatching { m.compileForSubagent() }.getOrNull()
    }

    private suspend fun afterRunMemory(state: SessionState) {
        val m = memory ?: return
        runCatching { m.afterRun(state, sessionTitle(rootSessionId(state))) }
    }

    /** Re-runs the loop on the existing history (used by /retry after trimming). */
    suspend fun resume(state: SessionState, confirm: ConfirmationGate, onEvent: (EngineEvent) -> Unit): RunOutcome {
        val lastUser = state.messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        state.checkpoint = state.userMessageCount()
        if (state.memorySnapshot == null) refreshMemory(state)
        val outcome = runWithGoal(state, ChatText.stripReminders(lastUser), confirm, serialize(onEvent))
        afterRunMemory(state)
        return outcome
    }

    private fun serialize(onEvent: (EngineEvent) -> Unit): (EngineEvent) -> Unit {
        val lock = Any()
        return { e -> synchronized(lock) { onEvent(e) } }
    }

    private fun withSwitchReminder(state: SessionState, agent: AgentDef, text: String): String {
        val previous = state.promptedAgent
        state.promptedAgent = agent.name
        val note = when {
            previous != null && previous != agent.name ->
                "The user switched you from the ${previous} agent to the ${agent.name} agent. Restrictions and instructions that came with the ${previous} agent no longer apply." +
                    (agent.reminder?.let { "\n\n$it" } ?: "")
            previous == null && agent.reminder != null && state.messages.none { it.role == Role.ASSISTANT } -> agent.reminder
            else -> null
        } ?: return text
        return ChatText.reminder(note) + "\n\n" + text
    }

    // ---- Goal rounds ---------------------------------------------------------------------

    private suspend fun runWithGoal(state: SessionState, input: String, confirm: ConfirmationGate, emit: (EngineEvent) -> Unit): RunOutcome {
        var outcome = loop(state, input, confirm, emit)
        val store = goals ?: return outcome
        while (outcome == RunOutcome.REPLIED || outcome == RunOutcome.FINISHED) {
            val goal = store.get(state.id)?.takeIf { it.active } ?: break
            val s = settings()
            val maxRounds = s.agent.goalMaxRounds.coerceAtLeast(1)
            if (goal.rounds >= maxRounds) {
                pauseGoal(store, state, goal, "reached the limit of $maxRounds rounds", emit)
                break
            }
            val verdict = try { judge(state, goal, s) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emit(EngineEvent.Notice("Goal check failed: ${e.message}. The goal stays active; send a message to continue.", true))
                break
            }
            val reason = verdict.reason.ifBlank { "(no reason given)" }
            when (verdict.verdict.lowercase()) {
                "done" -> {
                    val done = goal.copy(status = Goal.COMPLETE, rounds = goal.rounds + 1, reason = reason)
                    store.set(state.id, done)
                    emit(EngineEvent.GoalChanged(done))
                    emit(EngineEvent.Notice("🎯 Goal met: $reason"))
                    break
                }
                "blocked", "paused" -> { pauseGoal(store, state, goal.copy(rounds = goal.rounds + 1), reason, emit); break }
                else -> {
                    val worked = state.messages.takeLastWhile { it.role != Role.USER }.any { !it.toolCalls.isNullOrEmpty() }
                    val stalls = if (worked) 0 else goal.stalls + 1
                    val next = goal.copy(rounds = goal.rounds + 1, stalls = stalls, reason = reason)
                    if (stalls >= MAX_GOAL_STALLS) { pauseGoal(store, state, next, "no progress in $stalls rounds in a row", emit); break }
                    store.set(state.id, next)
                    emit(EngineEvent.GoalChanged(next))
                    emit(EngineEvent.Notice("🎯 Goal round ${next.rounds}/$maxRounds — not done yet: $reason"))
                    val feedback = buildString {
                        append("Reviewer feedback: ").append(reason)
                        verdict.next_step?.takeIf { it.isNotBlank() }?.let { append("\nSuggested next step: ").append(it) }
                    }
                    val text = persona.goalPrompt(PersonaLoader.GOAL_CONTINUE, goal.objective, feedback)
                        ?: "Continue working toward the goal: ${goal.objective}\n\n$feedback"
                    state.messages += ChatMessage(Role.USER, ChatText.reminder(text))
                    state.finished = false
                    outcome = loop(state, input, confirm, emit)
                }
            }
        }
        return outcome
    }

    private fun pauseGoal(store: GoalStore, state: SessionState, goal: Goal, reason: String, emit: (EngineEvent) -> Unit) {
        val paused = goal.copy(status = Goal.PAUSED, reason = reason)
        store.set(state.id, paused)
        emit(EngineEvent.GoalChanged(paused))
        emit(EngineEvent.Notice("🎯 Goal paused: $reason. Use `/goal resume` to continue."))
    }

    /** A separate, tool-less reviewer call that judges only evidence in the conversation. */
    private suspend fun judge(state: SessionState, goal: Goal, s: AppSettings): GoalVerdict {
        val prompt = persona.goalPrompt(PersonaLoader.GOAL_JUDGE, goal.objective)
            ?: "Decide whether this goal is met: ${goal.objective}. Reply with one line of JSON: {\"verdict\":\"done|continue|blocked\",\"reason\":\"...\"}"
        val transcript = compactor.transcript(state.messages.toList()).takeLast(JUDGE_TRANSCRIPT)
        val request = ChatRequest(
            model = s.provider.model,
            messages = listOf(ChatMessage(Role.SYSTEM, prompt), ChatMessage(Role.USER, "<conversation>\n$transcript\n</conversation>\n\nGive your verdict now as one line of JSON.")),
            temperature = 0f,
            stream = false,
        )
        val reply = client.chat(request).content
        return GoalVerdict.parse(reply) ?: GoalVerdict("continue", "the reviewer reply could not be read: ${reply.take(200)}")
    }

    // ---- Main loop -----------------------------------------------------------------------

    private class RunScope(val agent: AgentDef) {
        val recentCalls = ArrayDeque<String>()
    }

    private suspend fun loop(state: SessionState, input: String, confirm: ConfirmationGate, emit: (EngineEvent) -> Unit): RunOutcome {
        var verified = false
        val agent = agentFor(state)
        val scope = RunScope(agent)
        state.tools.snapshot = fileHistory?.let { h -> { f: File -> runCatching { h.snapshot(state.id, state.checkpoint, f) } } }
        val gateLock = Mutex()
        try {
            val maxTurns = (agent.maxSteps ?: settings().provider.maxTurns).coerceAtLeast(1)
            // MCP servers start (or restart after a config change) before the first request of a run.
            mcp?.let { m -> runCatching { m.ensureConnected() }.getOrNull()?.forEach { emit(EngineEvent.Notice(it, true)) } }
            for (turn in 1..maxTurns) {
                val s = settings()
                emit(EngineEvent.TurnStarted(turn))
                deliverJobNotices(state, emit)
                val result = requestTurn(state, s, agent, input, emit, withTools = true)

                val assistant = ChatMessage(Role.ASSISTANT, result.content.ifEmpty { null }, result.toolCalls.ifEmpty { null })
                state.messages += assistant
                emit(EngineEvent.AssistantMessage(assistant))

                if (result.toolCalls.isEmpty()) {
                    if (agent.verify && !verified && result.content.isNotBlank()) {
                        verified = true
                        state.messages += ChatMessage(Role.USER, ChatText.reminder(persona.verifyInstructions()))
                        continue
                    }
                    return RunOutcome.REPLIED
                }

                val outputs = executeAll(result.toolCalls, result.finishReason, state, s, confirm, emit, scope, gateLock, parentCallId = null,
                    allowed = { isOffered(it, state, s, agent) }, depth = 0)
                var finishSummary: String? = null
                result.toolCalls.forEachIndexed { i, call ->
                    val r = outputs[i]
                    state.messages += ChatMessage(Role.TOOL, r.output, toolCallId = call.id, name = call.function.name)
                    if (r.finish) finishSummary = r.output
                }
                if (finishSummary != null) {
                    state.finished = true
                    emit(EngineEvent.Finished(finishSummary!!))
                    return RunOutcome.FINISHED
                }
            }
            // Out of steps: one last call without tools, so the user gets a summary instead of silence.
            finalSummary(state, settings(), agent, emit)
            emit(EngineEvent.TurnLimitReached(maxTurns))
            return RunOutcome.TURN_LIMIT
        } catch (e: CancellationException) {
            repairDanglingToolCalls(state)
            throw e
        } catch (e: Exception) {
            repairDanglingToolCalls(state)
            emit(EngineEvent.Failed(e.message ?: e::class.java.simpleName))
            return RunOutcome.FAILED
        }
    }

    private suspend fun finalSummary(state: SessionState, s: AppSettings, agent: AgentDef, emit: (EngineEvent) -> Unit) {
        val prompt = persona.readOptional(PersonaLoader.MAX_STEPS) ?: return
        state.messages += ChatMessage(Role.USER, ChatText.reminder(prompt))
        runCatching {
            val r = requestTurn(state, s, agent, "", emit, withTools = false)
            if (r.content.isNotBlank()) {
                val m = ChatMessage(Role.ASSISTANT, r.content)
                state.messages += m
                emit(EngineEvent.AssistantMessage(m))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** One model call for the main agent, with compaction before it and recovery from context overflow. */
    private suspend fun requestTurn(state: SessionState, s: AppSettings, agent: AgentDef, input: String, emit: (EngineEvent) -> Unit, withTools: Boolean): ChatResult {
        var overflowRetried = false
        while (true) {
            val systemMessage = persona.buildSystemMessage(systemContext(state, s, input, agent))
            maybeCompact(state, systemMessage, s, emit, force = false)
            state.lastSystemMessage = systemMessage
            val schemas = if (withTools) schemasFor(s, state.disabledTools) { isOffered(it, state, s, agent) } else emptyList()
            val request = ChatRequest(
                model = agent.model ?: s.provider.model,
                messages = listOf(ChatMessage(Role.SYSTEM, systemMessage)) + state.messages,
                tools = schemas.ifEmpty { null },
                temperature = agent.temperature ?: s.provider.temperature,
                maxTokens = s.provider.maxTokens.takeIf { it > 0 },
                stream = true,
            )
            state.lastRequestJson = HarnessJson.encodeToString(request)
            try {
                val result = client.chat(request, { delta -> emit(EngineEvent.AssistantDelta(delta)) }) { info ->
                    emit(EngineEvent.Notice(retryNotice(info)))
                }
                state.lastResponseHeaders = result.headers
                state.lastUsage = result.usage
                return result
            } catch (e: ProviderException) {
                if (!overflowRetried && isContextOverflow(e)) {
                    overflowRetried = true
                    emit(EngineEvent.Notice("The provider says the context is too long. Compacting and trying again."))
                    if (maybeCompact(state, systemMessage, s, emit, force = true)) continue
                }
                throw e
            }
        }
    }

    /** Tool schemas with prompt-file descriptions; the task tool also lists the subagents it can start. */
    private fun schemasFor(s: AppSettings, disabled: Set<String>, include: (String) -> Boolean): List<ToolSchema> {
        val list = tools.schemas(persona) { include(it.name) }
        val subs = agents?.subagents().orEmpty()
        if (subs.isEmpty()) return list
        return list.map { sc ->
            if (sc.function.name != "task") sc else sc.copy(function = sc.function.copy(
                description = sc.function.description + "\n\nAvailable agents (pass one as `agent`):\n" +
                    subs.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "(no description)" }}" }
            ))
        }
    }

    private fun retryNotice(info: RetryInfo): String = when (info.kind) {
        RetryKind.OFFLINE -> NOTICE_OFFLINE
        RetryKind.ONLINE -> NOTICE_ONLINE
        RetryKind.BACKOFF -> "Retrying in ${info.delayMs / 1000}s (attempt ${info.attempt}): ${info.reason}"
    }

    private fun isContextOverflow(e: ProviderException): Boolean {
        if (e.statusCode !in setOf(400, 413, 422)) return false
        val t = (e.body + " " + e.message).lowercase()
        return listOf("context length", "context_length", "maximum context", "context window", "too many tokens", "prompt is too long",
            "reduce the length", "input is too long", "exceeds the model", "max_prompt_tokens", "token limit").any { it in t }
    }

    private fun deliverJobNotices(state: SessionState, emit: (EngineEvent) -> Unit) {
        val done = jobs?.takeUnreported(state.id).orEmpty()
        for (j in done) {
            val status = if (j.killed) "was stopped" else "finished with exit code ${j.exitCode}"
            val tail = JobManager.tail(j.log, 3000).trim()
            state.messages += ChatMessage(Role.USER, ChatText.reminder(
                "Background job #${j.id} $status after ${j.seconds}s: ${j.command}\nLog: ${workspace.relative(j.log)}\nLast output:\n```\n$tail\n```"
            ))
            emit(EngineEvent.Notice("Background job #${j.id} $status: `${j.command.take(80)}`"))
        }
    }

    // ---- Tool execution ------------------------------------------------------------------

    /** Runs a reply's tool calls, letting consecutive parallel-safe calls run side by side. */
    private suspend fun executeAll(
        calls: List<ToolCall>,
        finishReason: String?,
        state: SessionState,
        s: AppSettings,
        confirm: ConfirmationGate,
        emit: (EngineEvent) -> Unit,
        scope: RunScope,
        gateLock: Mutex,
        parentCallId: String?,
        allowed: (String) -> Boolean,
        depth: Int,
    ): List<ToolResult> {
        val results = arrayOfNulls<ToolResult>(calls.size)
        suspend fun one(i: Int): ToolResult {
            val call = calls[i]
            val cut = truncatedCallError(call, finishReason, s)
            if (cut != null) {
                emit(EngineEvent.ToolStarted(call.id, call.function.name, call.function.arguments, parentCallId))
                emit(EngineEvent.ToolFinished(call.id, call.function.name, cut, parentCallId))
                return cut
            }
            return executeCall(call, state, s, confirm, emit, scope, gateLock, parentCallId, allowed, depth)
        }
        fun safe(call: ToolCall): Boolean {
            val name = ToolRepair.resolveToolName(call.function.name, tools.names) ?: return false
            return s.agent.parallelTools && tools.get(name)?.parallelSafe == true && truncatedCallError(call, finishReason, s) == null
        }
        var i = 0
        while (i < calls.size) {
            if (safe(calls[i])) {
                var j = i
                while (j < calls.size && safe(calls[j])) j++
                if (j - i == 1) results[i] = one(i)
                else coroutineScope { (i until j).map { k -> async { results[k] = one(k) } }.awaitAll() }
                i = j
            } else {
                results[i] = one(i)
                i++
            }
        }
        return results.map { it ?: ToolResult.error("tool did not run") }
    }

    /** Forces compaction now (used by /compact). Returns null if there was nothing to compact. */
    suspend fun compactNow(state: SessionState, onEvent: (EngineEvent) -> Unit = {}): CompactionResult? {
        val s = settings()
        val r = compactor.compact(state.messages.toList(), s.provider.model) ?: return null
        state.messages.clear()
        state.messages += r.messages
        onEvent(EngineEvent.Compacted(r.summary, r.removedCount))
        return r
    }

    fun estimateTokens(state: SessionState): Int =
        compactor.estimateTokens(state.lastSystemMessage, state.messages)

    /**
     * Over the threshold: first clear old tool outputs (cheap, no model call); if that is not enough,
     * summarise. [force] skips the threshold (the provider already said it is too long).
     */
    private suspend fun maybeCompact(state: SessionState, systemMessage: String, s: AppSettings, emit: (EngineEvent) -> Unit, force: Boolean): Boolean {
        if (!s.agent.compaction && !force) return false
        val limit = (s.provider.contextWindow * s.agent.contextThreshold.coerceIn(0.05f, 1f)).toInt()
        val estimate = compactor.estimateTokens(systemMessage, state.messages)
        if (!force && !compactor.shouldCompact(estimate, s.provider.contextWindow, s.agent.contextThreshold)) return false
        val pruned = compactor.prune(state.messages)
        if (pruned > 0) {
            val after = compactor.estimateTokens(systemMessage, state.messages)
            emit(EngineEvent.Notice("Cleared $pruned old tool outputs to save context (~${estimate / 1000}k → ${after / 1000}k tokens)."))
            if (!force && after < limit) return true
        }
        val before = state.messages.toList()
        val r = compactor.compact(before, s.provider.model) ?: return pruned > 0
        memory?.let { m -> runCatching { m.beforeCompaction(state, sessionTitle(rootSessionId(state)), before.take(r.removedCount)) } }
        state.messages.clear()
        state.messages += r.messages
        emit(EngineEvent.Compacted(r.summary, r.removedCount))
        return true
    }

    /** Runs a single tool directly, outside the model loop (used by /run). */
    suspend fun runToolDirect(
        state: SessionState,
        name: String,
        argumentsJson: String,
        confirm: ConfirmationGate,
        onEvent: (EngineEvent) -> Unit,
    ): ToolResult {
        val s = settings()
        val call = ToolCall(id = "manual_${System.currentTimeMillis()}", function = dev.harness.net.FunctionCall(name, argumentsJson))
        return executeCall(call, state, s, confirm, serialize(onEvent), RunScope(agentFor(state)), Mutex(), parentCallId = null, allowed = { true }, depth = 0)
    }

    /** Short title for a new conversation (prompts/title.md), or null when it cannot be made. */
    suspend fun generateTitle(firstMessage: String): String? {
        val prompt = persona.readOptional(PersonaLoader.TITLE) ?: return null
        val s = settings()
        val request = ChatRequest(
            model = s.provider.model,
            messages = listOf(ChatMessage(Role.SYSTEM, prompt), ChatMessage(Role.USER, firstMessage.take(4000))),
            temperature = 0.3f,
            maxTokens = null,
            stream = false,
            // A title needs no deep thinking: high effort on reasoning models made it slow or empty.
            effortOverride = "low",
        )
        val text = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(60_000) { client.chat(request).content }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val line = text.replace(Regex("(?s)<think>.*?</think>"), "").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return line.removePrefix("Title:").trim().trim('"', '\'', '*', '#', ' ').removeSuffix(".").take(60).takeIf { it.length >= 2 }
    }

    /**
     * A reply that stopped at the output cap (finish_reason "length") leaves the last tool call's
     * arguments cut mid-JSON. Running it would write half a file, so tell the model what happened instead.
     */
    internal fun truncatedCallError(call: ToolCall, finishReason: String?, s: AppSettings): ToolResult? {
        if (finishReason != null && finishReason != "length" && finishReason != "max_tokens") return null
        val raw = call.function.arguments.ifBlank { "{}" }
        if (ToolRepair.parseArguments(raw) != null) return null
        val cap = s.provider.maxTokens.takeIf { it > 0 }?.let { "$it" } ?: "the provider's limit"
        return ToolResult.error(
            "your reply hit the output token cap ($cap) and the arguments for '${call.function.name}' were cut off " +
                "after ${raw.length} characters, so nothing was run. Send it again in smaller pieces: " +
                "write the file with a first part, then add the rest with `write` and `append: true` (or several `edit` calls). " +
                "Keep each tool call under ~300 lines. The user can raise Max tokens in Settings → Provider."
        )
    }

    private suspend fun executeCall(
        call: ToolCall,
        state: SessionState,
        s: AppSettings,
        confirm: ConfirmationGate,
        emit: (EngineEvent) -> Unit,
        scope: RunScope,
        gateLock: Mutex,
        parentCallId: String?,
        allowed: (String) -> Boolean,
        depth: Int,
    ): ToolResult {
        val called = call.function.name
        emit(EngineEvent.ToolStarted(call.id, called, call.function.arguments, parentCallId))
        val safety = Safety(s.safety)
        val notes = ArrayList<String>()
        var name = called
        val result: ToolResult = kotlin.run {
            val resolved = ToolRepair.resolveToolName(called, tools.names.filter(allowed))
                ?: ToolRepair.resolveToolName(called, tools.names)
            if (resolved == null) {
                val offered = tools.names.filter(allowed)
                return@run ToolResult.error("unknown tool '$called'. Available tools: ${offered.joinToString(", ")}")
            }
            if (resolved != called) notes += "There is no \"$called\" tool, so this ran as \"$resolved\". Call \"$resolved\" directly next time."
            name = resolved
            val tool = tools.get(name)!!
            if (!allowed(name)) return@run ToolResult.error("tool '$name' is not available to the ${scope.agent.name} agent in this session")
            if (safety.toolBlocked(name)) return@run ToolResult.error("blocked by safety policy: tool:$name is in the blocklist")
            val parsed = ToolRepair.parseArguments(call.function.arguments)
                ?: return@run ToolResult.error("arguments are not valid JSON: ${call.function.arguments.take(200)}")
            val repaired = ToolRepair.repair(parsed, tool.parameters, name)
            if (repaired.notes.isNotEmpty()) notes += "Argument names fixed: ${repaired.notes.joinToString(", ")}. Use the schema's names next time."
            val args: JsonObject = repaired.args

            // Doom loop: the same call with the same arguments three times in a row.
            val signature = name + "\u0000" + canonical(args)
            val looping = scope.recentCalls.size >= 2 && scope.recentCalls.takeLast(2).all { it == signature }
            scope.recentCalls.addLast(signature)
            while (scope.recentCalls.size > 8) scope.recentCalls.removeFirst()

            val asker = permissionAsker(state, scope.agent, confirm, gateLock, s.agent.approvalMode)
            if (looping) {
                asker.check("doom_loop", listOf(name), "The agent is repeating itself",
                    "It called $name with the same arguments 3 times in a row:\n${call.function.arguments.take(800)}\n\nLet it continue?", listOf(name))
                    ?.let {
                        return@run ToolResult.error("you called $name with identical arguments three times in a row and the user stopped it ($it). " +
                            "Step back: re-read the error, try a different approach, or ask the user.")
                    }
            }
            if (safety.toolNeedsConfirmation(name)) {
                asker.check("!$name", listOf("*"), "Allow $name?", call.function.arguments.take(1500), listOf("*"))?.let { return@run ToolResult.error(it) }
            }
            if (name !in SELF_CHECKED) {
                val resource = primaryResource(args)
                asker.check(name, listOf(resource), "Allow $name?", call.function.arguments.take(1500), listOf(if (resource == "*") "*" else PermissionEngine.pathPattern(resource)))
                    ?.let { return@run ToolResult.error(it) }
            }

            val subRunner = if (s.agent.subagents && depth == 0) SubagentRunner { agentName, description, prompt, taskId ->
                runSubagent(agentName, description, prompt, taskId, call.id, state, s, confirm, emit, gateLock)
            } else null
            val ctx = ToolContext(
                workspace = workspace,
                safety = safety,
                agent = s.agent,
                todos = state.todos,
                confirm = confirm,
                http = http,
                android = androidContext,
                subagent = subRunner,
                session = state.tools,
                permission = asker,
                question = if (depth == 0) confirm as? QuestionGate else null,
                jobs = jobs,
                sessionId = rootSessionId(state),
                subagentNames = { agents?.subagents()?.map { it.name to it.description }.orEmpty() },
            )
            try {
                tool.execute(args, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolArgumentException) {
                ToolResult.error(e.message ?: "invalid arguments")
            } catch (e: dev.harness.data.prompt.PromptMissingException) {
                ToolResult.error(e.message ?: "prompt missing")
            } catch (e: Exception) {
                ToolResult.error("${e::class.java.simpleName}: ${e.message}")
            }
        }
        val final = if (notes.isEmpty()) result else result.copy(output = result.output + "\n\n" + notes.joinToString("\n"))
        if (parentCallId == null) {
            state.lastToolName = name
            state.lastToolResult = final.output
        }
        emit(EngineEvent.ToolFinished(call.id, called, final, parentCallId))
        return final
    }

    private fun rootSessionId(state: SessionState) = state.id.substringBefore("/sub-")

    private fun canonical(o: JsonObject): String = o.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun primaryResource(args: JsonObject): String =
        listOf("path", "pattern", "name", "url", "id").firstNotNullOfOrNull { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } } ?: "*"

    /** Rules → maybe a question to the user → null (go ahead) or the error for the model. */
    private fun permissionAsker(state: SessionState, agent: AgentDef, confirm: ConfirmationGate, gateLock: Mutex, mode: String = "ask") =
        PermissionAsker { action, resources, title, detail, always ->
            // "!action" always asks (unless remembered); "?action" only enforces deny rules.
            val forced = action.startsWith("!")
            val denyOnly = action.startsWith("?")
            val act = action.removePrefix("!").removePrefix("?")
            val checks = permissions.evaluateAll(act, resources, agent.permissions, state.remembered)
            checks.firstOrNull { it.effect == PermEffect.DENY }?.let { d ->
                return@PermissionAsker "blocked by the permission rule `${d.rule}` (${d.rule?.source ?: "rules"}) for `${d.resource.take(120)}`. " +
                    "Do not try to work around it; the user manages rules in prompts/permissions.md and the agent files."
            }
            if (denyOnly) return@PermissionAsker null
            // Settings → Permissions: "always" answers every question with yes (the loop guard
            // still asks); "edits" does that for reads and file edits only.
            if (mode == "always" && act != "doom_loop") return@PermissionAsker null
            if (mode == "edits" && act in AUTO_EDIT_ACTIONS) return@PermissionAsker null
            val rememberedAll = resources.ifEmpty { listOf("*") }.all { r -> state.remembered.any { k -> keyMatches(k, act, r) } }
            val ask = (forced && !rememberedAll) || checks.any { it.effect == PermEffect.ASK }
            if (!ask) return@PermissionAsker null
            val answer = gateLock.withLock { confirm.decide(ApprovalRequest(title, detail, act, always)) }
            when (answer) {
                Approval.Once -> null
                Approval.Always -> { always.ifEmpty { resources }.forEach { state.remembered += PermissionEngine.rememberKey(act, it) }; null }
                is Approval.Deny -> answer.feedback?.takeIf { it.isNotBlank() }
                    ?.let { "the user declined this $act call and said: \"$it\". Follow that instead." }
                    ?: "the user declined this $act call. Do not retry it as is; choose another approach or ask what they want."
            }
        }

    private fun keyMatches(key: String, action: String, resource: String): Boolean {
        val a = key.substringBefore('\u0000')
        val p = key.substringAfter('\u0000')
        return Wildcard.match(action, a) && Wildcard.match(resource, p)
    }

    // ---- Subagents -----------------------------------------------------------------------

    /** Runs (or continues) a subagent in its own conversation and returns its final text. */
    private suspend fun runSubagent(
        agentName: String?,
        description: String,
        prompt: String,
        taskId: String?,
        parentCallId: String,
        parent: SessionState,
        s: AppSettings,
        confirm: ConfirmationGate,
        emit: (EngineEvent) -> Unit,
        gateLock: Mutex,
    ): SubagentResult {
        val existing = taskId?.let { id -> parent.subSessions[id] ?: parent.subSessions.values.firstOrNull { it.id.endsWith(id) } }
        val def = (agentName ?: existing?.agent)?.let { n -> agents?.get(n)?.takeIf { it.kind.canBeSubagent } }
            ?: agents?.get(agentName ?: DEFAULT_SUBAGENT)?.takeIf { it.kind.canBeSubagent }
            ?: agents?.get(DEFAULT_SUBAGENT)
            ?: AgentDef(DEFAULT_SUBAGENT, "", AgentKind.SUBAGENT, ToolSelector(false, ToolRegistry.SUBAGENT_DEFAULT, emptySet()), emptyList(), "", source = "built-in")
        val subTools = tools.names.filter { it !in SUBAGENT_NEVER && it !in parent.disabledTools && def.tools.allows(it) }
        val sub = existing?.state ?: SessionState(id = "${rootSessionId(parent)}/sub-${subIds.incrementAndGet()}", todos = TodoStore(), agent = def.name)
        sub.checkpoint = parent.checkpoint
        sub.tools.snapshot = parent.tools.snapshot
        parent.subSessions[sub.id] = SubSession(sub.id, def.name, sub)
        sub.messages += ChatMessage(Role.USER, prompt)
        val env = environment(def.model ?: s.provider.model)
        val instructions = projectInstructions()
        val maxTurns = (def.maxSteps ?: s.provider.maxTurns).coerceAtLeast(1)
        val scope = RunScope(def)
        var lastText = ""
        suspend fun call(withTools: Boolean): ChatResult {
            val system = persona.buildSubagentSystemMessage(env, subTools, def, instructions, parent.memoryForSubagents)
            val request = ChatRequest(
                model = def.model ?: s.provider.model,
                messages = listOf(ChatMessage(Role.SYSTEM, system)) + sub.messages,
                tools = if (withTools) tools.schemas(persona) { it.name in subTools }.ifEmpty { null } else null,
                temperature = def.temperature ?: s.provider.temperature,
                maxTokens = s.provider.maxTokens.takeIf { it > 0 },
                stream = true,
            )
            return client.chat(request, { delta -> emit(EngineEvent.SubagentDelta(parentCallId, delta)) }) { info ->
                emit(EngineEvent.SubagentDelta(parentCallId, when (info.kind) {
                    RetryKind.OFFLINE -> "\n[no internet, paused]\n"
                    RetryKind.ONLINE -> "\n[back online, continuing]\n"
                    RetryKind.BACKOFF -> "\n[retrying in ${info.delayMs / 1000}s: ${info.reason}]\n"
                }))
            }
        }
        for (turn in 1..maxTurns) {
            val result = call(withTools = true)
            sub.messages += ChatMessage(Role.ASSISTANT, result.content.ifEmpty { null }, result.toolCalls.ifEmpty { null })
            if (result.content.isNotBlank()) lastText = result.content
            if (result.toolCalls.isEmpty()) return SubagentResult(result.content, sub.id, def.name)
            val outputs = executeAll(result.toolCalls, result.finishReason, sub, s, confirm, emit, scope, gateLock, parentCallId,
                allowed = { it in subTools }, depth = depth(sub))
            result.toolCalls.forEachIndexed { i, c -> sub.messages += ChatMessage(Role.TOOL, outputs[i].output, toolCallId = c.id, name = c.function.name) }
        }
        persona.readOptional(PersonaLoader.MAX_STEPS)?.let { p ->
            sub.messages += ChatMessage(Role.USER, ChatText.reminder(Clock.nowLine() + "\n\n" + p))
            runCatching { call(withTools = false) }.getOrNull()?.content?.takeIf { it.isNotBlank() }?.let {
                sub.messages += ChatMessage(Role.ASSISTANT, it)
                return SubagentResult(it, sub.id, def.name)
            }
        }
        return SubagentResult(lastText.ifBlank { "ERROR: subagent '$description' reached its step limit ($maxTurns) without a final answer" }, sub.id, def.name)
    }

    private fun depth(state: SessionState) = if (state.id.contains("/sub-")) 1 else 0

    /** After a cancel or crash, make sure every assistant tool call has a tool result. */
    private fun repairDanglingToolCalls(state: SessionState) {
        val last = state.messages.indexOfLast { it.role == Role.ASSISTANT && !it.toolCalls.isNullOrEmpty() }
        if (last < 0) return
        val answered = state.messages.drop(last + 1).filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        state.messages[last].toolCalls?.filter { it.id !in answered }?.forEach {
            state.messages += ChatMessage(Role.TOOL, "ERROR: cancelled by the user before this tool finished", toolCallId = it.id, name = it.function.name)
        }
    }

    companion object {
        const val DEFAULT_SUBAGENT = "general"
        const val NOTICE_OFFLINE = "No internet. Paused, and it picks up where it left off once you're back online."
        const val NOTICE_ONLINE = "Back online. Continuing."
        private const val MAX_INSTRUCTIONS = 20_000
        private const val JUDGE_TRANSCRIPT = 60_000
        private const val MAX_GOAL_STALLS = 3
        /** Tools that run their own permission checks with precise resources. */
        private val SELF_CHECKED = setOf("read", "write", "edit", "multiedit", "patch", "bash", "web_fetch", "web_search", "task")
        /** Actions the "edits" approval mode lets through without asking. */
        private val AUTO_EDIT_ACTIONS = setOf("read", "edit", "write", "multiedit", "patch", "glob", "grep", "list", "external_directory")
        /** Tools a subagent never gets: no nesting, no questions to the user, no ending the parent's run. */
        val SUBAGENT_NEVER = setOf("task", "question", "finish", "memory")
    }
}
