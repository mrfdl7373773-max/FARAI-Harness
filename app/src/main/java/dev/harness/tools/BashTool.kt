package dev.harness.tools

import dev.harness.core.Safety
import dev.harness.core.SafetyDecision
import dev.harness.core.permission.PermissionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Somewhere other than Android's /system/bin/sh to run commands: Ubuntu or Alpine under PRoot. */
interface ShellBackend {
    val label: String
    val available: Boolean
    fun processFor(command: String, hostCwd: File): ProcessBuilder
}

class BashTool(private val backend: () -> ShellBackend? = { null }) : Tool {
    override val name = "bash"
    override val readOnly = true // offered to read-only subagents; mutations are still governed by permissions
    override val parallelSafe = false
    override val parameters: JsonObject = schema {
        string("command", required = true)
        string("description")
        string("workdir")
        integer("timeout_seconds")
        boolean("background")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.reqStr("command")
        if (command.isBlank()) return ToolResult.error("command is empty")
        var approved = false
        when (val d = ctx.safety.check(command)) {
            is SafetyDecision.Blocked -> return ToolResult.error("blocked by safety policy: ${d.reason}. The user controls this in Settings → Safety.")
            is SafetyDecision.NeedsConfirmation -> {
                val ok = ctx.permission.check("!bash", listOf(command), "Run this command?",
                    "$command\n\nHeld by the Safety rule \"${d.rule}\".", listOf(PermissionEngine.commandPattern(command)))
                if (ok != null) return ToolResult.error(ok)
                approved = true
            }
            SafetyDecision.Allowed -> Unit
        }
        val segments = Safety.segments(command)
        // Already approved once for this call: the rules can still refuse it, but not ask again.
        ctx.permission.check(if (approved) "?bash" else "bash", segments.ifEmpty { listOf(command) }, "Run this command?", command,
            segments.map { PermissionEngine.commandPattern(it) }.distinct())?.let { return ToolResult.error(it) }

        val cwd = args.str("workdir")?.takeIf { it.isNotBlank() }?.let { w ->
            val d = ctx.workspace.resolve(w)
            if (!d.isDirectory) return ToolResult.error("workdir '$w' is not a directory")
            FileOps.authorizeExternal(ctx, d)?.let { return ToolResult.error(it) }
            d
        } ?: ctx.workspace.root
        val linux = backend()?.takeIf { ctx.agent.useLinux && it.available }
        val pb = try {
            withContext(Dispatchers.IO) { linux?.processFor(command, cwd) ?: androidShell(command, cwd) }
        } catch (e: Exception) {
            return ToolResult.error("could not start ${linux?.label ?: "the shell"}: ${e.message}")
        }

        if (args.bool("background") == true) {
            val jobs = ctx.jobs ?: return ToolResult.error("background jobs are not available here; run it in the foreground")
            val job = try { withContext(Dispatchers.IO) { jobs.start(ctx.sessionId, command, pb) } } catch (e: Exception) {
                return ToolResult.error("could not start background job: ${e.message}")
            }
            return ToolResult(
                "started background job #${job.id}: $command\nOutput streams to ${ctx.workspace.relative(job.log)}.\n" +
                    "You will be told when it finishes (at the start of a later turn). Do not poll it in a loop: keep working on " +
                    "other things, or call job(action=\"wait\", id=${job.id}) when you need its result. Stop it with job(action=\"kill\", id=${job.id}).\n" +
                    "It keeps running after your turn ends and while the app is in the background. A server stays on until the user asks " +
                    "to stop or remove it: never kill it yourself when you finish."
            )
        }
        val timeout = (args.int("timeout_seconds") ?: ctx.agent.bashTimeoutSeconds).coerceIn(1, MAX_TIMEOUT)
        return runProcess(pb, timeout, ctx.agent.bashOutputCap, linux?.label, outputDir(ctx.workspace))
    }

    companion object {
        const val MAX_TIMEOUT = 1800
        private val SECRET_MARKERS = listOf("KEY", "TOKEN", "SECRET", "PASS", "CRED")

        fun shell(): String = listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).exists() } ?: "sh"

        fun outputDir(ws: Workspace): File = File(ws.root, ".farai/tool-output")

        fun androidShell(command: String, cwd: File): ProcessBuilder {
            val pb = ProcessBuilder(shell(), "-c", command).directory(cwd).redirectErrorStream(true)
            val env = pb.environment()
            env.keys.filter { k -> SECRET_MARKERS.any { k.uppercase().contains(it) } }.forEach { env.remove(it) }
            env["HOME"] = cwd.absolutePath
            env["PWD"] = cwd.absolutePath
            env["TMPDIR"] = File(cwd, ".tmp").apply { mkdirs() }.absolutePath
            env["AGENT"] = "1"
            env["FARAI"] = "1"
            return pb
        }

        suspend fun run(command: String, cwd: File, timeoutSeconds: Int, cap: Int): ToolResult =
            runProcess(androidShell(command, cwd), timeoutSeconds, cap, null, null)

        /**
         * Runs to completion. Output beyond [cap] characters is kept whole in a log file under
         * [spillDir] and the model sees the head and the (more useful) tail.
         */
        suspend fun runProcess(pb: ProcessBuilder, timeoutSeconds: Int, cap: Int, label: String?, spillDir: File? = null): ToolResult = withContext(Dispatchers.IO) {
            val spill = spillDir?.let { d ->
                d.mkdirs()
                cleanup(d)
                File(d, "bash-${System.currentTimeMillis()}.log")
            } ?: File.createTempFile("farai-bash", ".log")
            pb.redirectErrorStream(true)
            pb.redirectOutput(spill)
            val process = try { pb.start() } catch (e: Exception) {
                spill.delete()
                return@withContext ToolResult.error("could not start shell: ${e.message}")
            }
            val finished = try {
                runInterruptible { process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS) }
            } catch (e: Exception) {
                process.destroyForcibly()
                throw e
            }
            coroutineContext.ensureActive()
            if (!finished) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            val total = spill.length()
            val (body, kept) = render(spill, cap)
            val where = if (kept && spillDir != null) "\n[full output (${ReadTool.human(total)}) saved to ${spill.parentFile?.name}/${spill.name} under .farai/; read or grep it for the rest]" else ""
            if (!kept || spillDir == null) spill.delete()
            if (!finished) {
                return@withContext ToolResult(
                    "ERROR: timed out after ${timeoutSeconds}s (the command was killed)\n$body$where\n" +
                        "Retry with a larger timeout_seconds, or run it with background: true if it is a server or a long build.",
                    isError = true,
                )
            }
            val code = process.exitValue()
            val header = "exit code: $code" + (label?.let { " ($it)" } ?: "")
            val out = body.ifEmpty { "(no output)" }
            if (code == 0) ToolResult("$header\n$out$where")
            else ToolResult("ERROR: $header\n$out$where" + if (code == 127) (if (label != null) "\n(command not found: install it with " + (if (label.contains("Ubuntu", true)) "`apt-get install -y <package>`" else "`apk add <package>`") + ")" else "\n(command not found on this device)") else "", isError = true)
        }

        /** Whole text when small; otherwise head + tail, and true meaning "keep the log". */
        private fun render(f: File, cap: Int): Pair<String, Boolean> {
            val len = f.length()
            if (len <= cap) return f.readText(Charsets.UTF_8).trimEnd() to false
            val headSize = (cap * 0.35).toInt()
            val tailSize = cap - headSize
            val head = f.inputStream().use { s -> String(s.readNBytesCompat(headSize), Charsets.UTF_8) }.substringBeforeLast('\n')
            val tail = JobManager.tail(f, tailSize).substringAfter("]\n")
            val skipped = len - head.length - tail.length
            return "$head\n… [${ReadTool.human(skipped.coerceAtLeast(0))} of output omitted] …\n${tail.trimEnd()}" to true
        }

        private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
            return if (off == n) buf else buf.copyOf(off)
        }

        private fun cleanup(dir: File) {
            val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }
}
