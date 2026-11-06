package dev.harness.core.run

import dev.harness.core.ConfirmationGate
import dev.harness.core.EngineEvent
import dev.harness.core.Question
import dev.harness.core.QuestionGate
import dev.harness.core.SessionState
import dev.harness.core.permission.Approval
import dev.harness.core.permission.ApprovalRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A permission question waiting for the user, kept until a screen answers it. */
data class PendingApproval(
    val title: String,
    val detail: String,
    val answer: CompletableDeferred<Approval>,
    /** True when "Always allow" makes sense (the request named patterns to remember). */
    val canRemember: Boolean = false,
    val action: String = "",
)

/** Questions from the `question` tool waiting for the user. */
data class PendingQuestion(val questions: List<Question>, val answer: CompletableDeferred<List<List<String>>?>)

/** The gate a run hands the engine: permission questions and model questions both park on the run. */
interface InteractiveGate : ConfirmationGate, QuestionGate

/**
 * Runs agent loops in the application scope, so a run keeps going when the chat screen closes or
 * the app goes to the background (a foreground service keeps the process alive meanwhile).
 *
 * A screen that opens a session with a live run attaches to it: it shares the run's
 * [SessionState], replays nothing, and follows [Run.events] from that point on. History is saved
 * after every completed step, so even if the process dies the session is not empty.
 */
class AgentRunner(
    private val scope: CoroutineScope,
    /** Saves the session; called after each completed step and when the run ends. */
    private val save: suspend (SessionState) -> Unit,
    /** Called with the number of live runs whenever it changes (starts or stops the service). */
    private val onActiveChanged: (count: Int, title: String?) -> Unit,
    /** Called when a run ends or needs approval while no screen is attached. */
    private val onBackgroundEvent: (sessionId: String, kind: BackgroundEvent) -> Unit,
) {
    enum class BackgroundEvent { FINISHED, FAILED, NEEDS_APPROVAL }

    class Run(val state: SessionState) {
        internal var job: Job? = null
        private val _running = MutableStateFlow(true)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 512)
        /** Live events. Not replayed: an attaching screen rebuilds from [state] first. */
        val events: SharedFlow<EngineEvent> = _events.asSharedFlow()
        private val _approval = MutableStateFlow<PendingApproval?>(null)
        val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()
        private val _question = MutableStateFlow<PendingQuestion?>(null)
        val question: StateFlow<PendingQuestion?> = _question.asStateFlow()
        /** Number of screens currently showing this run. */
        @Volatile var attached: Int = 0
        @Volatile var lastError: String? = null
        @Volatile var title: String? = null

        internal fun emit(e: EngineEvent) { _events.tryEmit(e) }
        internal fun setApproval(a: PendingApproval?) { _approval.value = a }
        internal fun setQuestion(q: PendingQuestion?) { _question.value = q }
        internal fun markDone() { _running.value = false }
    }

    private val runs = ConcurrentHashMap<String, Run>()

    fun live(sessionId: String): Run? = runs[sessionId]?.takeIf { it.running.value }

    fun isRunning(sessionId: String): Boolean = live(sessionId) != null

    val activeCount: Int get() = runs.values.count { it.running.value }

    /**
     * Starts [block] for [state] unless that session already has a live run (then returns it).
     * The gate passed to [block] parks approval questions on the run until a screen answers.
     */
    fun start(
        state: SessionState,
        title: String?,
        block: suspend (gate: ConfirmationGate, onEvent: (EngineEvent) -> Unit) -> Unit,
    ): Run {
        live(state.id)?.let { return it }
        val run = Run(state).also { it.title = title }
        runs[state.id] = run
        // Steps are saved off the engine's thread, newest wins.
        val saves = Channel<Unit>(Channel.CONFLATED)
        val saver = scope.launch { for (u in saves) runCatching { save(state) } }
        val gate = object : InteractiveGate {
            override suspend fun confirm(title: String, detail: String): Boolean =
                decide(ApprovalRequest(title, detail)) !is Approval.Deny

            override suspend fun decide(request: ApprovalRequest): Approval {
                val answer = CompletableDeferred<Approval>()
                run.setApproval(PendingApproval(request.title, request.detail, answer, request.always.isNotEmpty(), request.action))
                if (run.attached == 0) onBackgroundEvent(state.id, BackgroundEvent.NEEDS_APPROVAL)
                return try { answer.await() } finally { run.setApproval(null) }
            }

            override suspend fun ask(questions: List<Question>): List<List<String>>? {
                val answer = CompletableDeferred<List<List<String>>?>()
                run.setQuestion(PendingQuestion(questions, answer))
                if (run.attached == 0) onBackgroundEvent(state.id, BackgroundEvent.NEEDS_APPROVAL)
                return try { answer.await() } finally { run.setQuestion(null) }
            }
        }
        val onEvent: (EngineEvent) -> Unit = { e ->
            when (e) {
                is EngineEvent.TurnStarted, is EngineEvent.AssistantMessage, is EngineEvent.ToolFinished,
                is EngineEvent.Compacted, is EngineEvent.Finished -> saves.trySend(Unit)
                is EngineEvent.Failed -> run.lastError = e.message
                else -> Unit
            }
            run.emit(e)
        }
        notifyActive()
        run.job = scope.launch {
            try {
                block(gate, onEvent)
            } finally {
                withContext(NonCancellable) {
                    saves.close()
                    saver.join()
                    runCatching { save(state) }
                    run.markDone()
                    runs.remove(state.id, run)
                    if (run.attached == 0) {
                        onBackgroundEvent(state.id, if (run.lastError != null) BackgroundEvent.FAILED else BackgroundEvent.FINISHED)
                    }
                    notifyActive()
                }
            }
        }
        return run
    }

    fun stop(sessionId: String) {
        val r = runs[sessionId] ?: return
        r.approval.value?.answer?.complete(Approval.Deny())
        r.question.value?.answer?.complete(null)
        r.job?.cancel()
    }

    fun stopAll() { runs.keys.toList().forEach(::stop) }

    private fun notifyActive() {
        val live = runs.values.filter { it.running.value }
        onActiveChanged(live.size, live.firstOrNull()?.title)
    }
}
