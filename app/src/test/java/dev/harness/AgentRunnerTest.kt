package dev.harness

import dev.harness.core.EngineEvent
import dev.harness.core.SessionState
import dev.harness.core.run.AgentRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AgentRunnerTest {
    private fun runner(saves: MutableList<Int>, active: MutableList<Int>, bg: MutableList<AgentRunner.BackgroundEvent>) = AgentRunner(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        save = { saves += 1 },
        onActiveChanged = { n, _ -> active += n },
        onBackgroundEvent = { _, k -> bg += k },
    )

    @Test fun runOutlivesScreenAndSavesEachStep() = runBlocking {
        val saves = CopyOnWriteArrayList<Int>(); val active = CopyOnWriteArrayList<Int>(); val bg = CopyOnWriteArrayList<AgentRunner.BackgroundEvent>()
        val r = runner(saves, active, bg)
        val st = SessionState("s1")
        val release = CompletableDeferred<Unit>()
        val run = r.start(st, "t") { _, emit ->
            emit(EngineEvent.TurnStarted(1))
            release.await()
            emit(EngineEvent.Finished("done"))
        }
        assertTrue(r.isRunning("s1"))
        // Starting again while live returns the same run instead of a second loop.
        assertTrue(r.start(st, "t") { _, _ -> error("must not run") } === run)
        release.complete(Unit)
        withTimeout(5_000) { run.running.first { !it } }
        delay(50)
        assertFalse(r.isRunning("s1"))
        assertTrue(saves.size >= 2)
        assertEquals(listOf(1, 0), active)
        assertEquals(listOf(AgentRunner.BackgroundEvent.FINISHED), bg)
    }

    @Test fun approvalWaitsForAScreenAndStopCancels() = runBlocking {
        val saves = CopyOnWriteArrayList<Int>(); val active = CopyOnWriteArrayList<Int>(); val bg = CopyOnWriteArrayList<AgentRunner.BackgroundEvent>()
        val r = runner(saves, active, bg)
        val run = r.start(SessionState("s2"), null) { gate, _ -> gate.confirm("rm", "rm -rf x"); delay(60_000) }
        val pending = withTimeout(5_000) { run.approval.first { it != null } }!!
        assertEquals("rm", pending.title)
        // The background notice is posted right after the approval appears; give it a moment.
        withTimeout(5_000) { while (AgentRunner.BackgroundEvent.NEEDS_APPROVAL !in bg) delay(10) }
        r.stop("s2")
        withTimeout(5_000) { run.running.first { !it } }
        assertTrue(pending.answer.isCompleted)
    }
}
