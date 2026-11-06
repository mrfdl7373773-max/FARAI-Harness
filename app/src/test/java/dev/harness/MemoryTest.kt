package dev.harness

import dev.harness.core.ConfirmationGate
import dev.harness.core.ContextCompactor
import dev.harness.core.Engine
import dev.harness.core.PersonaLoader
import dev.harness.core.RepoMapper
import dev.harness.core.SessionState
import dev.harness.core.memory.Bm25
import dev.harness.core.memory.MemoryException
import dev.harness.core.memory.MemoryFormat
import dev.harness.core.memory.MemoryRuntime
import dev.harness.core.memory.MemoryStore
import dev.harness.core.memory.RecallStore
import dev.harness.core.memory.Secrets
import dev.harness.data.settings.AgentSettings
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatMessage
import dev.harness.net.Role
import dev.harness.tools.ToolRegistry
import dev.harness.tools.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoryTest {
    private val seed = mapOf(
        "persona.md" to "---\nname: persona\ndescription: Who you are\n---\nYou are FARAI.\n",
        "human.md" to "---\nname: human\ndescription: Who the user is\n---\n(Nothing learned yet.)\n",
        "MEMORY.md" to "Map of what you remember.\n",
    )

    private fun store(project: File? = null) = MemoryStore(File(TestSupport.tempDir("mem"), "memory"), { project }, { seed }).apply { ensureSeeded() }

    @Test
    fun seedsOnceAndCompilesCoreBlocksWithSizes() {
        val s = store()
        assertTrue(File(s.root, "persona.md").isFile)
        File(s.root, "persona.md").delete()
        s.ensureSeeded() // marker: a file the user deleted is not brought back
        assertFalse(File(s.root, "persona.md").isFile)
        val blocks = s.coreBlocks()
        assertEquals(listOf("human"), blocks.map { it.name })
        assertTrue(blocks[0].limit > 0)
    }

    @Test
    fun createLinksFileFromFolderAndRootIndex() {
        val s = store()
        s.create("projects/farai.md", "Native Android coding agent.", "FARAI harness: architecture and gotchas", "test", "s1")
        val folderIdx = File(s.root, "projects/MEMORY.md").readText()
        assertTrue(folderIdx, folderIdx.contains("[farai](projects/farai.md)"))
        assertTrue(File(s.root, "MEMORY.md").readText().contains("projects/"))
        val text = File(s.root, "projects/farai.md").readText()
        assertEquals("FARAI harness: architecture and gotchas", MemoryFormat.parse(text).description)
        assertEquals("farai", MemoryFormat.parse(text).name)
        assertEquals(1, s.deferredDirs().size)
        // Creating again without overwrite is refused.
        assertThrows(MemoryException::class.java) { s.create("projects/farai.md", "x", "d", null, null) }
    }

    @Test
    fun replaceIsExactAndUnique() {
        val s = store()
        s.replace("human.md", "(Nothing learned yet.)", "- Name: Farrel\n- Reply in casual Indonesian.", false, "user said so", "s1")
        assertTrue(s.read("human.md").contains("casual Indonesian"))
        val e = assertThrows(MemoryException::class.java) { s.replace("human.md", "formal English", "x", false, null, null) }
        assertTrue(e.message!!.contains("not found"))
        s.append("human.md", "- Likes Indonesian.", null, null)
        assertThrows(MemoryException::class.java) { s.replace("human.md", "Indonesian", "x", false, null, null) }
        s.replace("human.md", "Indonesian", "Javanese", true, null, null)
        assertFalse(s.read("human.md").contains("Indonesian"))
    }

    @Test
    fun limitsSecretsAndPathsAreEnforced() {
        val s = MemoryStore(File(TestSupport.tempDir("mem"), "memory"), { null }, { seed }, dev.harness.core.memory.MemoryLimits(coreFile = 300, coreTotal = 2000)).apply { ensureSeeded() }
        val big = assertThrows(MemoryException::class.java) { s.append("human.md", "x".repeat(400), null, null) }
        assertTrue(big.message!!.contains("limit"))
        val key = assertThrows(MemoryException::class.java) { s.append("human.md", "key: sk-or-v1-abcdefghijklmnopqrstuvwxyz0123", null, null) }
        assertTrue(key.message!!.contains("secret"))
        assertThrows(MemoryException::class.java) { s.read("../etc/passwd") }
        assertThrows(MemoryException::class.java) { s.create("a/b/c/d.md", "x", "deep", null, null) }
        assertThrows(MemoryException::class.java) { s.create("topics/x.md", "body", null, null, null) } // description needed
        assertNull(Secrets.find("Farrel uses OpenRouter; the key lives in Settings."))
    }

    @Test
    fun historyAndRevert() {
        val s = store()
        val before = s.read("human.md")
        s.replace("human.md", "(Nothing learned yet.)", "Name: Farrel", false, "intro", "s1")
        val change = s.history("human.md").first()
        assertEquals("replace", change.op)
        assertEquals("intro", change.reason)
        s.revert(change.id, "user")
        assertEquals(before, s.read("human.md"))
        s.create("topics/x.md", "body", "desc", null, null)
        s.delete("topics/x.md", "stale", null)
        assertFalse(File(s.root, "topics/x.md").exists())
        s.revert(s.history("topics/x.md").first { it.op == "delete" }.id, null)
        assertTrue(File(s.root, "topics/x.md").exists())
    }

    @Test
    fun moveUpdatesIndexes() {
        val s = store()
        s.create("topics/android.md", "Gradle needs aapt2 override.", "Android build gotchas", null, null)
        s.move("topics/android.md", "projects/android.md", null, null)
        assertFalse(File(s.root, "topics/android.md").exists())
        assertTrue(File(s.root, "projects/MEMORY.md").readText().contains("android.md"))
    }

    @Test
    fun searchRanksRelevantLines() {
        val s = store()
        s.create("topics/builds.md", "Use GRADLE_USER_HOME on the sandbox.\nThe aapt2 binary must be aarch64.", "Build notes", null, null)
        s.create("topics/poems.md", "Poems should not read as AI-written.", "Poetry preferences", null, null)
        val hits = s.search("aapt2 aarch64")
        assertTrue(hits.isNotEmpty())
        assertEquals("topics/builds.md", hits[0].path)
        assertTrue(Bm25.rank("rilis", listOf("tanggal rilisnya", "lain")).first().first == 0)
    }

    @Test
    fun projectScopeLivesInWorkspace() {
        val proj = File(TestSupport.tempDir("ws"), ".farai/memory")
        val s = store(proj)
        s.create("project/overview.md", "Kotlin + Compose app.", "What this project is", null, null)
        assertTrue(File(proj, "overview.md").isFile)
        assertEquals(1, s.coreBlocks(MemoryStore.Scope.PROJECT).size)
        assertTrue(s.read("project/").contains("overview"))
    }

    private fun runtime(client: FakeChatClient, s: MemoryStore, settings: AppSettings = AppSettings(), sessions: List<RecallStore.RecallSession> = emptyList()): MemoryRuntime {
        val repo = TestSupport.repo()
        return MemoryRuntime(
            store = s,
            recall = RecallStore(File(s.root, ".recall")) { sessions },
            prompts = repo,
            client = client,
            settings = { settings },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transcript = { msgs -> msgs.joinToString("\n") { "${it.role}: ${it.content}" } },
        )
    }

    @Test
    fun compileShowsBlocksIndexAndGuide() = runTest {
        val s = store()
        s.create("people/ana.md", "Ana reviews PRs.", "Ana, a teammate", null, null)
        val text = runtime(FakeChatClient(), s).compile()!!
        assertTrue(text.contains("<memory scope=\"agent\""))
        assertTrue(text.contains("<human path=\"human.md\" chars="))
        assertTrue(text.contains("people/"))
        assertTrue(text.contains("# Memory")) // memory.md guide from the prompts
        val off = runtime(FakeChatClient(), s, AppSettings(agent = AgentSettings(memory = false))).compile()
        assertNull(off)
    }

    @Test
    fun recallFindsLiveAndArchivedMessagesWithDates() = runTest {
        val s = store()
        val sessions = listOf(RecallStore.RecallSession("s1", "Build fixes", 0L, listOf(
            ChatMessage(Role.USER, "<system-reminder>\nCurrent date and time: Thursday, 5 November 2026, 10:00 (Asia/Jakarta, UTC+07:00).\n</system-reminder>\n\nkenapa aapt2 gagal di sandbox?"),
            ChatMessage(Role.ASSISTANT, "Because the aapt2 binary was x86; use the aarch64 build."),
        )))
        val rt = runtime(FakeChatClient(), s, sessions = sessions)
        rt.recall.archive("s2", "Old chat", listOf(ChatMessage(Role.USER, "my favourite colour is mint green")))
        val out = rt.runRecallTool(buildJsonObject { put("query", JsonPrimitive("aapt2 sandbox")) }, null)
        assertTrue(out, out.contains("session=s1"))
        assertTrue(out, out.contains("5 November 2026"))
        val archived = rt.runRecallTool(buildJsonObject { put("query", JsonPrimitive("favourite colour")) }, null)
        assertTrue(archived, archived.contains("mint green") && archived.contains("compacted"))
        val ranged = rt.recall.search("aapt2", after = "2026-11-06")
        assertTrue(ranged.isEmpty())
        val around = rt.runRecallTool(buildJsonObject { put("session", JsonPrimitive("s1")); put("around", JsonPrimitive(0)) }, null)
        assertTrue(around.contains("aarch64"))
    }

    @Test
    fun nudgeDetectsPreferencesAndCorrections() = runTest {
        val rt = runtime(FakeChatClient(), store())
        assertNotNull(rt.nudgeFor("mulai sekarang selalu jawab pakai bahasa santai ya"))
        assertNotNull(rt.nudgeFor("kenapa kamu lupa lagi soal tanggal?"))
        assertNotNull(rt.nudgeFor("tolong lupakan alamat lamaku"))
        assertNull(rt.nudgeFor("build ulang apk-nya"))
    }

    @Test
    fun reflectionEditsMemoryWithTools() = runTest {
        val s = store()
        val client = FakeChatClient(
            FakeChatClient.tool("r1", "memory", """{"action":"str_replace","path":"human.md","old_text":"(Nothing learned yet.)","new_text":"- Reply in casual Indonesian.","reason":"user asked on 6 Nov 2026"}"""),
            FakeChatClient.text("Saved the language preference."),
        )
        val rt = runtime(client, s)
        val report = rt.reflect("s1", "Chat", listOf(ChatMessage(Role.USER, "jawab pakai bahasa santai"), ChatMessage(Role.ASSISTANT, "Oke!")), "test")
        assertEquals(1, report.changes)
        assertTrue(s.read("human.md").contains("casual Indonesian"))
        assertEquals("reflection:s1", s.history("human.md").first().source)
        assertTrue(client.requests[0].messages[1].content!!.contains("<transcript>"))
    }

    @Test
    fun engineInjectsMemoryAndRunsTheTool() = runTest {
        val s = store()
        val client = FakeChatClient(
            FakeChatClient.tool("m1", "memory", """{"action":"append","path":"human.md","text":"- Name: Farrel","reason":"intro"}"""),
            FakeChatClient.text("Noted."),
            FakeChatClient.text("Hi Farrel."),
        )
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        val ws = Workspace(File(TestSupport.tempDir("ws"), "workspace"))
        val settings = AppSettings(agent = AgentSettings(reflectEvery = -1))
        lateinit var rt: MemoryRuntime
        val tools = ToolRegistry(ToolRegistry.core() + ToolRegistry.memory { rt })
        rt = runtime(client, s, settings)
        val engine = Engine(
            client = client, persona = persona, tools = tools, compactor = ContextCompactor(client, persona), repoMapper = RepoMapper(),
            workspace = ws, http = OkHttpClient(), settings = { settings }, environment = { TestSupport.env(it, ws.root.path) }, memory = rt,
        )
        val state = SessionState("s1")
        engine.run(state, "namaku Farrel", ConfirmationGate { _, _ -> true }) {}
        assertTrue(s.read("human.md").contains("Name: Farrel"))
        val sys1 = client.requests[0].messages[0].content!!
        assertTrue(sys1.contains("<human path=\"human.md\""))
        assertFalse(sys1.contains("Name: Farrel"))
        // The first user message got the memory nudge ("namaku …" is a fact).
        assertTrue(client.requests[0].messages.last().content!!.contains("lasting preference or fact"))
        assertTrue(client.requests[0].tools!!.any { it.function.name == "memory" })
        // Next message: the edit is in the prompt.
        engine.run(state, "halo", ConfirmationGate { _, _ -> true }) {}
        assertTrue(client.requests.last().messages[0].content!!.contains("Name: Farrel"))
    }

    @Test
    fun compactionArchivesForRecall() = runTest {
        val s = store()
        val rt = runtime(FakeChatClient(), s)
        val state = SessionState("s9")
        rt.beforeCompaction(state, "T", listOf(ChatMessage(Role.USER, "the deploy key rotates on Fridays")))
        val hits = rt.recall.search("deploy Fridays")
        assertEquals(1, hits.size)
        assertTrue(hits[0].unit.archived)
    }

    @Test
    fun parseDayHandlesFormats() {
        assertEquals(20261106, RecallStore.parseDay("2026-11-06"))
        assertEquals(20261106, RecallStore.parseDay("Friday, 6 November 2026, 14:36"))
        assertEquals(20261105, RecallStore.parseDay("5 November 2026"))
    }
}
