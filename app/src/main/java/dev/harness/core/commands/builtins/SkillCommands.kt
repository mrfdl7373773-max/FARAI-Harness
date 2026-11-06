package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.skills.Skill
import dev.harness.core.skills.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** /skills: manage Agent Skills (SKILL.md folders) in .farai/skills. */
class SkillsCommand : Command {
    override val name = "skills"
    override val aliases = listOf("skill")
    override val group = CommandGroup.TOOLS
    override val usage = "[list|show <name>|use <name> [task]|new <name> [description]|import <path> [--force]|on|off|remove <name>|path]"
    override val description = "Agent Skills (SKILL.md): list, create, import, use, turn on or off"
    override val subcommands = listOf("list", "show", "use", "new", "import", "on", "off", "remove", "path")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult = withContext(Dispatchers.IO) {
        val root = ctx.workspaceRoot ?: return@withContext CommandResult.Error("Skills need a workspace.")
        val store = SkillStore(root)
        val sub = inv.arg(0)?.lowercase() ?: "list"
        fun need(i: Int = 1): Skill? = inv.arg(i)?.let(store::find)
        fun missing(): CommandResult = CommandResult.Error(
            "No skill named '${inv.arg(1) ?: ""}'. Installed: " + store.list().joinToString(", ") { it.name }.ifEmpty { "none" },
        )
        when (sub) {
            "list", "ls" -> {
                val all = store.list()
                if (all.isEmpty()) return@withContext CommandResult.Output(
                    "No skills yet.\n\nCreate one with `/skills new <name> <description>`, import a folder, .md or .zip with " +
                        "`/skills import <path>`, or just ask the agent to make one.",
                )
                CommandResult.Output(buildString {
                    appendLine("**Skills** (${all.count { it.enabled }} on, ${all.size} total)")
                    appendLine()
                    all.forEach { s ->
                        appendLine("- ${if (s.enabled) "●" else "○"} **${s.name}** — ${s.description.ifBlank { "(no description)" }}")
                    }
                    appendLine()
                    append("Folder: `${SkillStore.PRIMARY}/` in the workspace. `/skills show <name>` to read one.")
                })
            }
            "show", "cat" -> {
                val s = need() ?: return@withContext missing()
                val files = store.files(s)
                CommandResult.Output(buildString {
                    appendLine("**${s.name}** ${if (s.enabled) "(on)" else "(off)"} · `${s.source}/${s.dir.name}/`")
                    appendLine()
                    appendLine(fence(s.file.readText(), "markdown"))
                    if (files.isNotEmpty()) { appendLine(); append("Files: " + files.joinToString(", ") { "`$it`" }) }
                })
            }
            "use", "run" -> {
                val s = need() ?: return@withContext missing()
                val task = inv.rest(2)
                CommandResult.SendToAgent(
                    "Use the \"${s.name}\" skill (load it with the skill tool first)" + if (task.isBlank()) "." else " for this: $task",
                    note = "Using skill ${s.name}",
                )
            }
            "new", "create" -> {
                val n = inv.arg(1) ?: return@withContext CommandResult.Error("Usage: /skills new <name> [description]")
                val s = try { store.create(n, inv.rest(2)) } catch (e: Exception) { return@withContext CommandResult.Error(e.message ?: "could not create") }
                CommandResult.Output(
                    "Created **${s.name}** at `${SkillStore.PRIMARY}/${s.dir.name}/SKILL.md`. Edit it with the agent " +
                        "(\"improve the ${s.name} skill so it…\") or any editor; changes apply on the next message.",
                )
            }
            "import", "add", "install" -> {
                val raw = inv.args.drop(1).filter { it != "--force" && it != "-f" }.joinToString(" ").trim()
                if (raw.isEmpty()) return@withContext CommandResult.Error("Usage: /skills import <folder | file.md | file.zip> [--force]  (workspace path or /sdcard/…)")
                val src = resolvePath(raw, root, ctx.sharedStorage)
                    ?: return@withContext CommandResult.Error("Not found: $raw")
                val force = inv.args.any { it == "--force" || it == "-f" }
                val added = try { store.import(src, force) } catch (e: Exception) { return@withContext CommandResult.Error(e.message ?: "import failed") }
                CommandResult.Output("Imported " + added.joinToString(", ") { "**${it.name}**" } + ". The agent sees it from the next message.")
            }
            "on", "off", "enable", "disable" -> {
                val on = sub == "on" || sub == "enable"
                val s = need() ?: return@withContext missing()
                store.setEnabled(s, on)
                CommandResult.Output("**${s.name}** is now ${if (on) "on" else "off"}.")
            }
            "remove", "rm", "delete" -> {
                val s = need() ?: return@withContext missing()
                store.remove(s)
                CommandResult.Output("Removed **${s.name}**.")
            }
            "path", "where" -> CommandResult.Output("Skills folder: `${store.home.absolutePath}`\n(also read: `${SkillStore.CLAUDE}/` in the workspace)")
            else -> {
                // "/skill pdf-report make a report from data.csv" uses the skill directly.
                val s = store.find(sub) ?: return@withContext CommandResult.Error("Unknown sub-command or skill '$sub'. Try /skills list.")
                val task = inv.rest(1)
                CommandResult.SendToAgent(
                    "Use the \"${s.name}\" skill (load it with the skill tool first)" + if (task.isBlank()) "." else " for this: $task",
                    note = "Using skill ${s.name}",
                )
            }
        }
    }

    private fun resolvePath(raw: String, root: File, shared: File?): File? {
        val p = raw.trim().removeSurrounding("\"")
        val candidates = buildList {
            when {
                p.startsWith("/workspace/") -> add(File(root, p.removePrefix("/workspace/")))
                p.startsWith("/sdcard/") && shared != null -> add(File(shared, p.removePrefix("/sdcard/")))
                p.startsWith("/") -> add(File(p))
                else -> { add(File(root, p)); shared?.let { add(File(it, p)) } }
            }
        }
        return candidates.firstOrNull { it.exists() }
    }
}
