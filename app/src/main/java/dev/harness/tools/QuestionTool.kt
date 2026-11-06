package dev.harness.tools

import dev.harness.core.Question
import dev.harness.core.QuestionOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Asks the user one or more multiple-choice questions and waits for the answers. */
class QuestionTool : Tool {
    override val name = "question"
    override val parameters: JsonObject = schema {
        array("questions", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") { put("type", "string") }
                putJsonObject("header") { put("type", "string") }
                putJsonObject("multiple") { put("type", "boolean") }
                putJsonObject("options") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("label") { put("type", "string") }
                            putJsonObject("description") { put("type", "string") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("label")) }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("question")) }
        }, required = true)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val gate = ctx.question ?: return ToolResult.error("asking the user is not available here (subagents cannot ask; decide yourself or report back)")
        val qs = args.array("questions")?.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val text = o.str("question")?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val options = o.array("options")?.mapNotNull { op ->
                runCatching { op.jsonObject }.getOrNull()?.let { QuestionOption(it.str("label").orEmpty(), it.str("description").orEmpty()) }
                    ?: op.asString()?.let { QuestionOption(it) }
            }?.filter { it.label.isNotBlank() }.orEmpty()
            Question(text, o.str("header").orEmpty(), options, o.bool("multiple") == true)
        }.orEmpty()
        if (qs.isEmpty()) return ToolResult.error("'questions' must hold at least one {question, options} item")
        val answers = gate.ask(qs) ?: return ToolResult.error("the user dismissed the question without answering. Do not ask again right away: continue with your best judgement or stop and explain what you need.")
        val formatted = qs.mapIndexed { i, q -> "\"${q.question}\" = \"${answers.getOrNull(i)?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(no answer)"}\"" }
        return ToolResult("The user answered: ${formatted.joinToString("; ")}. Continue with these answers in mind.")
    }
}
