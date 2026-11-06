package dev.harness

import dev.harness.core.ConfirmationGate
import dev.harness.core.ContextCompactor
import dev.harness.core.Engine
import dev.harness.core.EngineEvent
import dev.harness.core.FileHistory
import dev.harness.core.GoalVerdict
import dev.harness.core.PersonaLoader
import dev.harness.core.RepoMapper
import dev.harness.core.SessionState
import dev.harness.core.ToolRepair
import dev.harness.core.agents.AgentKind
import dev.harness.core.agents.AgentStore
import dev.harness.core.permission.Approval
import dev.harness.core.permission.ApprovalRequest
import dev.harness.core.permission.PermEffect
import dev.harness.core.permission.PermissionEngine
import dev.harness.core.permission.PermissionRules
import dev.harness.data.settings.AppSettings
import dev.harness.net.ChatMessage
import dev.harness.net.Role
import dev.harness.tools.Patch
import dev.harness.tools.PatchException
import dev.harness.tools.TextMatch
import dev.harness.tools.ToolRegistry
import dev.harness.tools.Workspace
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HarnessV2Test {

    // ---- Edit matching ----

    @Test fun exactAndUnicodeAndIndentTiers() {
        val src = "fun a() {\n    val s = \u201Chello\u201D\n    return s\n}\n"
        val r1 = TextMatch.replace(src, "val s = \"hello\"", "val s = \"hi\"", false, "a.kt") as TextMatch.Result.Ok
        assertEquals("unicode", r1.tier)
        assertTrue(r1.text.contains("val s = \"hi\""))
        val r2 = TextMatch.replace(src, "val s = \u201Chello\u201D\nreturn s", "val t = 1\nreturn t", false, "a.kt") as TextMatch.Result.Ok
        assertEquals("indentation", r2.tier)
        assertTrue(r2.text.contains("    val t = 1\n    return t"))
    }

    @Test fun crlfPreservedAndMissHint() {
        val src = "one\r\ntwo\r\nthree\r\n"
        val ok = TextMatch.replace(src, "two\nthree", "2\n3", false, "f") as TextMatch.Result.Ok
        assertEquals("one\r\n2\r\n3\r\n", ok.text)
        val fail = TextMatch.replace("alpha\nbeta gamma\ndelta\n", "beta  gama\ndelta", "x", false, "f") as TextMatch.Result.Fail
        assertTrue(fail.message, fail.message.contains("closest lines") || fail.message.contains("not found"))
        val dup = TextMatch.replace("x\nx\n", "x", "y", false, "f") as TextMatch.Result.Fail
        assertTrue(dup.message.contains("2 places"))
        val applied = TextMatch.replace("hello world, this is new text here\n", "old text", "this is new text here", false, "f") as TextMatch.Result.Fail
        assertTrue(applied.message.contains("already"))
    }

    // ---- Patch ----

    @Test fun patchUpdateAddDelete() {
        val ops = Patch.parse("""
            *** Begin Patch
            *** Add File: a.txt
            +hello
            *** Update File: b.py
            @@ def greet():
            -    print("Hi")
            +    print("Hello")
            *** Delete File: c.txt
            *** End Patch
        """.trimIndent())
        assertEquals(3, ops.size)
        val upd = ops[1] as Patch.Op.Update
        val out = Patch.apply("x = 1\ndef greet():\n    print(\"Hi\")\n", upd.hunks, "b.py")
        assertEquals("x = 1\ndef greet():\n    print(\"Hello\")\n", out)
        assertEquals("hello\n", (ops[0] as Patch.Op.Add).content)
    }

    @Test(expected = PatchException::class) fun patchMismatchThrows() {
        val ops = Patch.parse("*** Begin Patch\n*** Update File: a\n@@\n-nope\n+yes\n*** End Patch")
        Patch.apply("something else\n", (ops[0] as Patch.Op.Update).hunks, "a")
    }

    // ---- Permissions ----

    @Test fun lastMatchWinsAndRemember() {
        val rules = PermissionRules.parse("```\n* * allow\nbash git push* ask\nedit secret/* deny\n```\nprose that says allow", "t")
        assertEquals(3, rules.size)
        val engine = PermissionEngine { rules }
        assertEquals(PermEffect.ALLOW, engine.evaluate("bash", "ls -la", emptyList(), emptySet()).effect)
        assertEquals(PermEffect.ASK, engine.evaluate("bash", "git push origin main", emptyList(), emptySet()).effect)
        assertEquals(PermEffect.DENY, engine.evaluate("edit", "secret/key.txt", emptyList(), emptySet()).effect)
        val remembered = setOf(PermissionEngine.rememberKey("bash", "git push*"))
        assertEquals(PermEffect.ALLOW, engine.evaluate("bash", "git push", emptyList(), remembered).effect)
        val agentRules = PermissionRules.parse("edit * deny", "agent")
        assertEquals(PermEffect.DENY, engine.evaluate("edit", "a.txt", agentRules, emptySet()).effect)
        assertEquals("git push*", PermissionEngine.commandPattern("git push origin"))
    }

    // ---- Tool argument repair ----

    @Test fun repairsCommonMistakes() {
        assertEquals("bash", ToolRepair.resolveToolName("shell", listOf("bash", "read")))
        assertEquals("edit", ToolRepair.resolveToolName("str_replace", listOf("edit")))
        assertNull(ToolRepair.resolveToolName("teleport", listOf("bash")))
        val schema = dev.harness.tools.ReadTool().parameters
        val parsed = ToolRepair.parseArguments("```json\n{\"filePath\": \"a.txt\", \"limit\": \"20\", \"junk\": 1,}\n```")!!
        val fixed = ToolRepair.repair(parsed, schema, "read").args
        assertEquals("a.txt", fixed["path"]!!.jsonPrimitive.content)
        assertEquals(20, fixed["limit"]!!.jsonPrimitive.int)
        assertFalse(fixed.containsKey("junk"))
        val double = ToolRepair.parseArguments(JsonPrimitive("{\"path\":\"x\"}").toString())
        assertEquals("x", double!!["path"]!!.jsonPrimitive.content)
    }

    // ---- Agents ----

    @Test fun shippedAgentsParse() {
        val store = AgentStore(TestSupport.repo()) { null }
        val names = store.all().map { it.name }
        assertTrue(names.containsAll(listOf("build", "plan", "verify", "explore", "general", "reviewer")))
        assertEquals(listOf("build", "plan", "verify"), store.primary().map { it.name }.take(3))
        val plan = store.get("plan")!!
        assertTrue(plan.permissions.any { it.action == "edit" && it.effect == PermEffect.DENY })
        assertFalse(plan.tools.allows("finish"))
        assertNotNull(plan.reminder)
        val explore = store.get("explore")!!
        assertEquals(AgentKind.SUBAGENT, explore.kind)
        assertFalse(explore.tools.allows("write"))
        assertTrue(store.get("verify")!!.verify)
        assertEquals("plan", AgentStore.legacyName("PLANNING"))
    }

    // ---- Undo ----

    @Test fun fileHistoryRestores() {
        val dir = TestSupport.tempDir("hist")
        val h = FileHistory(File(dir, "h"))
        val f = File(dir, "a.txt").apply { writeText("v1") }
        val created = File(dir, "new.txt")
        h.snapshot("s", 1, f); f.writeText("v2")
        h.snapshot("s", 1, f); f.writeText("v3") // second snapshot at the same checkpoint is ignored
        h.snapshot("s", 1, created); created.writeText("x")
        val restored = h.restoreFrom("s", 1)
        assertEquals(2, restored.size)
        assertEquals("v1", f.readText())
        assertFalse(created.exists())
    }

    @Test fun goalVerdictParsing() {
        assertEquals("done", GoalVerdict.parse("blah {\"verdict\":\"done\",\"reason\":\"tests pass\"} end")!!.verdict)
        assertEquals("continue", GoalVerdict.parse("I think we should continue.")!!.verdict)
    }

    // ---- Engine behaviors ----

    private fun engine(client: FakeChatClient, settings: AppSettings = AppSettings(), rules: String = ""): Pair<Engine, Workspace> {
        val repo = TestSupport.repo()
        val persona = PersonaLoader(repo)
        val ws = Workspace(File(TestSupport.tempDir("ws"), "workspace"))
        val e = Engine(
            client = client, persona = persona, tools = ToolRegistry(ToolRegistry.core()),
            compactor = ContextCompactor(client, persona), repoMapper = RepoMapper(), workspace = ws,
            http = OkHttpClient(), settings = { settings }, environment = { TestSupport.env(it, ws.root.path) },
            agents = AgentStore(repo) { ws.root },
            permissions = PermissionEngine { PermissionRules.parse(rules, "test") },
        )
        return e to ws
    }

    @Test fun writeRefusesUnreadFileAndAliasesRun() = runTest {
        val client = FakeChatClient(
            FakeChatClient.tool("a", "write_file", """{"filePath":"x.txt","content":"new"}"""),
            FakeChatClient.text("ok"),
        )
        val (e, ws) = engine(client)
        File(ws.root, "x.txt").writeText("user work")
        val st = SessionState("w1")
        e.run(st, "go", ConfirmationGate { _, _ -> true }) { }
        val out = st.messages.first { it.role == Role.TOOL }.content!!
        assertTrue(out, out.startsWith("ERROR") && out.contains("not read"))
        assertTrue(out.contains("ran as \"write\""))
        assertEquals("user work", File(ws.root, "x.txt").readText())
    }

    @Test fun doomLoopAsksAndDenialFeedbackReachesModel() = runTest {
        val loop = Array(3) { FakeChatClient.tool("g$it", "glob", """{"pattern":"*.none"}""") }
        val client = FakeChatClient(*loop, FakeChatClient.text("stopped"))
        val (e, _) = engine(client, rules = "doom_loop * ask")
        val asked = mutableListOf<String>()
        val gate = object : ConfirmationGate {
            override suspend fun confirm(title: String, detail: String) = true
            override suspend fun decide(request: ApprovalRequest): Approval { asked += request.action; return Approval.Deny("try grep instead") }
        }
        val st = SessionState("d1")
        e.run(st, "loop", gate) { }
        assertEquals(listOf("doom_loop"), asked)
        val third = st.messages.filter { it.role == Role.TOOL }[2].content!!
        assertTrue(third, third.contains("try grep instead"))
    }

    @Test fun planAgentCannotEdit() = runTest {
        val client = FakeChatClient(
            FakeChatClient.tool("a", "write", """{"path":"src/a.txt","content":"x"}"""),
            FakeChatClient.text("ok"),
        )
        val (e, ws) = engine(client)
        val st = SessionState("p1", agent = "plan")
        e.run(st, "plan it", ConfirmationGate { _, _ -> true }) { }
        val out = st.messages.first { it.role == Role.TOOL }.content!!
        assertTrue(out, out.contains("blocked by the permission rule"))
        assertFalse(File(ws.root, "src/a.txt").exists())
        // The plan reminder is attached to the first message.
        assertTrue(st.messages.first().content!!.contains("<system-reminder>"))
    }

    @Test fun parallelReadsAllReturnInOrder() = runTest {
        val r = dev.harness.net.ChatResult("", listOf(
            dev.harness.net.ToolCall("1", function = dev.harness.net.FunctionCall("read", """{"path":"a.txt"}""")),
            dev.harness.net.ToolCall("2", function = dev.harness.net.FunctionCall("read", """{"path":"b.txt"}""")),
        ), "tool_calls")
        val client = FakeChatClient(r, FakeChatClient.text("done"))
        val (e, ws) = engine(client)
        File(ws.root, "a.txt").writeText("AAA")
        File(ws.root, "b.txt").writeText("BBB")
        val st = SessionState("par")
        val events = mutableListOf<EngineEvent>()
        e.run(st, "read both", ConfirmationGate { _, _ -> true }) { events += it }
        val tools = st.messages.filter { it.role == Role.TOOL }
        assertEquals(listOf("1", "2"), tools.map { it.toolCallId })
        assertTrue(tools[0].content!!.contains("AAA") && tools[1].content!!.contains("BBB"))
    }

    @Test fun pruneClearsOldToolOutput() {
        val msgs = mutableListOf<ChatMessage>()
        repeat(10) { msgs += ChatMessage(Role.TOOL, "x".repeat(50_000), toolCallId = "t$it", name = "read") }
        val cleared = ContextCompactor(FakeChatClient(), PersonaLoader(TestSupport.repo())).prune(msgs)
        assertTrue(cleared >= 6)
        assertTrue(msgs.last().content!!.length == 50_000)
        assertTrue(msgs.first().content!!.startsWith(ContextCompactor.PRUNED))
    }
}
