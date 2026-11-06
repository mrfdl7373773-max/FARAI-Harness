package dev.harness

import dev.harness.tools.ToolArgumentException
import dev.harness.tools.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceTest {
    private val ws = Files.createTempDirectory("ws").toFile()
    private val sd = Files.createTempDirectory("sd").toFile()

    @Test
    fun guestPathsMapToHost() {
        val w = Workspace(ws) { listOf(sd) }
        assertEquals(File(ws, "a/b.txt").canonicalFile, w.resolve("/workspace/a/b.txt"))
        assertEquals(File(sd, "Download/x.py").canonicalFile, w.resolve("/sdcard/Download/x.py"))
        assertEquals(File(sd, "x").canonicalPath, w.relative(File(sd, "x")))
        assertEquals("a/b.txt", w.relative(File(ws, "a/b.txt")))
    }

    @Test(expected = ToolArgumentException::class)
    fun sharedStorageNeedsPermission() {
        Workspace(ws).resolve("/sdcard/Download/x.py")
    }

    @Test(expected = ToolArgumentException::class)
    fun stillRejectsOtherPaths() {
        Workspace(ws) { listOf(sd) }.resolve("/etc/passwd")
    }
}
