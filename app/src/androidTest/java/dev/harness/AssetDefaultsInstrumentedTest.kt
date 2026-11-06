package dev.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.harness.core.AssetDefaultsSource
import dev.harness.core.PromptRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssetDefaultsInstrumentedTest {
    @Test
    fun apkShipsEveryDefaultPrompt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = AssetDefaultsSource(ctx.assets)
        val paths = src.list()
        listOf("identity.md", "system.md", "compaction.md", "planning.md", "verify.md", "refusal.md",
            "error_recovery.md", "onboarding.md", "tools/bash.md", "tools/task.md", "tools/web.md").forEach {
            assertTrue("missing $it", it in paths)
        }
        val repo = PromptRepository(File(ctx.cacheDir, "prompts-test-${System.nanoTime()}"), src)
        assertTrue(repo.ensureInitialized())
        assertTrue(repo.read("identity.md").isNotBlank())
    }
}
