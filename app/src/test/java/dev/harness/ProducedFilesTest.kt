package dev.harness

import dev.harness.core.files.ProducedFiles
import dev.harness.net.ChatMessage
import dev.harness.net.FunctionCall
import dev.harness.net.Role
import dev.harness.net.ToolCall
import dev.harness.tools.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProducedFilesTest {
    private fun ws(): Workspace = Workspace(Files.createTempDirectory("ws").toFile())

    private fun call(id: String, name: String, args: String) =
        ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCall(id, function = FunctionCall(name, args))))

    @Test fun findsWrittenFilesAndMentionedZipAndFolder() {
        val w = ws()
        File(w.root, "app").mkdirs(); File(w.root, "app/main.py").writeText("print(1)")
        File(w.root, "out.zip").writeBytes(byteArrayOf(1, 2))
        val turn = listOf(
            ChatMessage(Role.USER, "make it"),
            call("1", "write", """{"path":"app/main.py","content":"print(1)"}"""),
            ChatMessage(Role.TOOL, "wrote 8 bytes", toolCallId = "1"),
            call("2", "bash", """{"command":"zip -r out.zip app"}"""),
            ChatMessage(Role.TOOL, "ok", toolCallId = "2"),
            ChatMessage(Role.ASSISTANT, "Done. Project is in `app/` and packed as out.zip. See https://example.com/x.html"),
        )
        val got = ProducedFiles.byTurn(turn, w).values.single().map { it.display }
        assertEquals(listOf("app/main.py", "app/", "out.zip"), got)
        assertTrue(ProducedFiles.byTurn(turn, w).values.single().first { it.display == "app/" }.isDirectory)
    }

    @Test fun skipsFailedWritesAndMissingFiles() {
        val w = ws()
        val turn = listOf(
            ChatMessage(Role.USER, "x"),
            call("1", "write", """{"path":"a.py","content":""}"""),
            ChatMessage(Role.TOOL, "ERROR: denied", toolCallId = "1"),
            ChatMessage(Role.ASSISTANT, "I wrote `ghost.md`."),
        )
        assertTrue(ProducedFiles.byTurn(turn, w).isEmpty())
    }

    @Test fun splitsByTurn() {
        val w = ws()
        File(w.root, "a.md").writeText("a"); File(w.root, "b.md").writeText("b")
        val msgs = listOf(
            ChatMessage(Role.USER, "1"), ChatMessage(Role.ASSISTANT, "made a.md"),
            ChatMessage(Role.USER, "2"), ChatMessage(Role.ASSISTANT, "made b.md"),
        )
        val got = ProducedFiles.byTurn(msgs, w)
        assertEquals(setOf(1, 3), got.keys)
        assertEquals("b.md", got[3]!!.single().display)
    }
}
