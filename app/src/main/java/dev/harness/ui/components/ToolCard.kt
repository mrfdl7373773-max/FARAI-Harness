package dev.harness.ui.components

import dev.harness.net.HarnessJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One-line human summary of the arguments ("src/main.kt", "ls -la"). */
fun argSummary(name: String, arguments: String): String {
    val o = runCatching { HarnessJson.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return arguments.take(80)
    fun s(k: String) = (o[k] as? JsonPrimitive)?.content
    return when (name) {
        "bash" -> s("description")?.takeIf { it.isNotBlank() }?.let { d -> "$d · ${s("command")?.take(60)}" } ?: s("command")
        "read", "write", "edit" -> s("path")
        "multiedit" -> s("path")?.let { p -> "$p · ${(o["edits"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0} edits" }
        "patch" -> s("patch")?.let { p -> Regex("(?m)^\\*\\*\\* (?:Add|Update|Delete) File:\\s*(.+)$").findAll(p).map { it.groupValues[1].trim() }.toList().joinToString(", ") }
        "question" -> (o["questions"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { q -> ((q as? kotlinx.serialization.json.JsonObject)?.get("question") as? JsonPrimitive)?.content }
        "job" -> listOfNotNull(s("action"), s("id")?.let { "#$it" }).joinToString(" ")
        "glob" -> s("pattern")
        "grep" -> s("pattern")?.let { p -> "/$p/" + (s("include")?.let { " in $it" } ?: "") }
        "web_fetch" -> s("url") ?: (o["urls"] as? kotlinx.serialization.json.JsonArray)?.let { "${it.size} pages" }
        "web_search" -> s("query")
        "task" -> s("description")?.let { d -> s("agent")?.let { "$it · $d" } ?: d }
        "todo" -> s("action") ?: "update"
        "intent_open" -> s("uri")
        "notification_post" -> s("title")
        "finish" -> "summary"
        else -> null
    } ?: o.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(80)
}

