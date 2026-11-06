package dev.harness

import dev.harness.core.DirectoryDefaultsSource
import dev.harness.core.EnvironmentInfo
import dev.harness.core.PromptRepository
import dev.harness.net.ChatClient
import dev.harness.net.ChatRequest
import dev.harness.net.ChatResult
import dev.harness.net.FunctionCall
import dev.harness.net.ToolCall
import java.io.File
import java.nio.file.Files

/** Shared helpers for JVM unit tests. */
object TestSupport {
    /** The shipped defaults, read straight from src/main/assets (unit tests run in app/). */
    val defaultsDir: File = listOf(File("src/main/assets/defaults/prompts"), File("app/src/main/assets/defaults/prompts"))
        .first { it.isDirectory }

    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    fun repo(): PromptRepository =
        PromptRepository(File(tempDir("prompts"), "prompts"), DirectoryDefaultsSource(defaultsDir)).apply { ensureInitialized() }

    fun env(model: String = "test-model", cwd: String = "/tmp/ws") = EnvironmentInfo(
        appVersion = "test", androidVersion = "14", apiLevel = 34, abi = "arm64-v8a",
        device = "JVM", cwd = cwd, model = model, dateTime = "2026-01-01 00:00", timeZone = "UTC",
    )
}

/** Scripted ChatClient: returns queued results in order and records every request. */
class FakeChatClient(vararg results: ChatResult) : ChatClient {
    private val queue = ArrayDeque(results.toList())
    val requests = mutableListOf<ChatRequest>()
    var models: List<String> = listOf("test-model", "other-model")

    override suspend fun chat(request: ChatRequest, onDelta: (String) -> Unit): ChatResult {
        requests += request
        val r = queue.removeFirstOrNull() ?: ChatResult("(no more scripted replies)", emptyList(), "stop")
        if (r.content.isNotEmpty()) r.content.chunked(4).forEach(onDelta)
        return r
    }

    override suspend fun listModels(): List<String> = models

    companion object {
        fun text(t: String) = ChatResult(t, emptyList(), "stop")
        fun tool(id: String, name: String, args: String) = ChatResult("", listOf(ToolCall(id, function = FunctionCall(name, args))), "tool_calls")
    }
}
