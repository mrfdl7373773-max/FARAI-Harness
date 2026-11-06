package dev.harness

import dev.harness.core.DirectoryDefaultsSource
import dev.harness.core.PromptRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PromptUpgradeTest {
    @Test
    fun untouchedDefaultsFollowAppUpdatesButEditsAreKept() {
        val defaults = Files.createTempDirectory("defaults").toFile()
        File(defaults, "a.md").writeText("v1 a")
        File(defaults, "b.md").writeText("v1 b")
        val root = File(Files.createTempDirectory("p").toFile(), "prompts")
        PromptRepository(root, DirectoryDefaultsSource(defaults)).ensureInitialized()
        val repo1 = PromptRepository(root, DirectoryDefaultsSource(defaults))
        repo1.write("b.md", "my edit")

        // App update ships new defaults.
        File(defaults, "a.md").writeText("v2 a")
        File(defaults, "b.md").writeText("v2 b")
        val repo2 = PromptRepository(root, DirectoryDefaultsSource(defaults))
        repo2.ensureInitialized()
        assertEquals("v2 a", repo2.read("a.md"))
        assertEquals("my edit", repo2.read("b.md"))
    }
}
