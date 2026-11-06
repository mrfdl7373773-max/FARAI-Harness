package dev.harness.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shell commands started with `background: true`: dev servers, long builds, test suites. Output
 * streams to a log file inside the workspace (so `read`/`grep` can look at it), and finished jobs
 * are announced to the model at the start of its next turn.
 */
class JobManager(private val logDir: () -> File) {

    class Job(
        val id: Int,
        val sessionId: String,
        val command: String,
        val log: File,
        val startedAt: Long,
        internal val process: Process,
    ) {
        @Volatile var exitCode: Int? = null
        @Volatile var finishedAt: Long = 0
        @Volatile var killed: Boolean = false
        @Volatile internal var reported: Boolean = false
        val running: Boolean get() = exitCode == null
        val seconds: Long get() = ((if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt) / 1000
    }

    /** Called when a job starts or ends; the app keeps a foreground service up while any job runs. */
    @Volatile var onChange: (() -> Unit)? = null

    private val ids = AtomicInteger(0)
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun start(sessionId: String, command: String, pb: ProcessBuilder): Job {
        val dir = logDir().apply { mkdirs() }
        val id = ids.incrementAndGet()
        val log = File(dir, "job-$id-${System.currentTimeMillis()}.log")
        pb.redirectErrorStream(true)
        pb.redirectOutput(log)
        val p = pb.start()
        val job = Job(id, sessionId, command, log, System.currentTimeMillis(), p)
        jobs[id] = job
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            job.exitCode = code
            job.finishedAt = System.currentTimeMillis()
            runCatching { onChange?.invoke() }
        }.apply { isDaemon = true; name = "farai-job-$id"; start() }
        runCatching { onChange?.invoke() }
        return job
    }

    /** Every running job, across sessions (dev servers stay up until stopped). */
    fun allRunning(): List<Job> = jobs.values.filter { it.running }.sortedBy { it.id }

    fun killAll() = allRunning().forEach { kill(it.id) }

    fun get(id: Int): Job? = jobs[id]
    fun list(sessionId: String): List<Job> = jobs.values.filter { it.sessionId == sessionId }.sortedBy { it.id }
    fun running(sessionId: String): List<Job> = list(sessionId).filter { it.running }

    fun kill(id: Int): Boolean {
        val j = jobs[id] ?: return false
        if (!j.running) return false
        j.killed = true
        j.process.destroy()
        Thread { Thread.sleep(1500); if (j.running) j.process.destroyForcibly() }.apply { isDaemon = true; start() }
        return true
    }

    fun killSession(sessionId: String) = running(sessionId).forEach { kill(it.id) }

    /** Waits for a job to finish, up to [timeoutSeconds]. Returns true when it finished. */
    suspend fun wait(id: Int, timeoutSeconds: Int): Boolean {
        val j = jobs[id] ?: return false
        return withTimeoutOrNull(timeoutSeconds * 1000L) {
            while (j.running) delay(250)
            true
        } ?: false
    }

    /** Finished jobs of this session that the model has not been told about yet. */
    fun takeUnreported(sessionId: String): List<Job> =
        list(sessionId).filter { !it.running && !it.reported }.onEach { it.reported = true }

    fun markReported(job: Job) { job.reported = true }

    companion object {
        /** The last [maxChars] characters of a log, starting on a line boundary. */
        fun tail(file: File, maxChars: Int): String {
            if (!file.isFile) return ""
            val len = file.length()
            if (len <= maxChars) return file.readText()
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - maxChars)
                val bytes = ByteArray(maxChars)
                raf.readFully(bytes)
                val s = String(bytes, Charsets.UTF_8)
                return "… [${len - maxChars} earlier bytes in ${file.name}]\n" + s.substringAfter('\n', s)
            }
        }
    }
}
