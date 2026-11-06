package dev.harness.tools

import dev.harness.core.skills.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Loads an Agent Skill's SKILL.md (or one of its helper files) on demand. */
class SkillTool : Tool {
    override val name = "skill"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("name", required = true)
        string("file")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val store = SkillStore(ctx.workspace.root)
        val wanted = args.reqStr("name")
        val skill = store.find(wanted)
            ?: return@withContext ToolResult.error(
                "no skill named '$wanted'. Available: " + store.enabled().joinToString(", ") { it.name }.ifEmpty { "(none)" },
            )
        if (!skill.enabled) return@withContext ToolResult.error("skill '${skill.name}' is turned off by the user")
        val folder = ctx.workspace.relative(skill.dir)
        val file = args.str("file")?.takeIf { it.isNotBlank() }
        if (file != null) {
            val text = try { store.readFile(skill, file) } catch (e: Exception) { return@withContext ToolResult.error(e.message ?: "cannot read $file") }
            return@withContext ToolResult("<skill_file skill=\"${skill.name}\" path=\"$folder/$file\">\n$text\n</skill_file>")
        }
        val files = store.files(skill)
        ToolResult(buildString {
            appendLine("<skill name=\"${skill.name}\" folder=\"$folder\">")
            appendLine(store.body(skill))
            if (files.isNotEmpty()) {
                appendLine()
                appendLine("Helper files in $folder/ (load with skill(name, file) or use their path):")
                files.forEach { appendLine("- $it") }
            }
            append("</skill>")
        })
    }
}
