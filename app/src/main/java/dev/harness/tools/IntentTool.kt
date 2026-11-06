package dev.harness.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class IntentTool : Tool {
    override val name = "intent_open"
    override val parameters: JsonObject = schema { string("uri", required = true) }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = ctx.android ?: return ToolResult.error("intents are not available in this environment")
        val raw = args.reqStr("uri").trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri?.scheme.isNullOrEmpty()) return ToolResult.error("URI must include a scheme, e.g. https://")
        if (uri!!.scheme.equals("file", true) || uri.scheme.equals("content", true)) {
            return ToolResult.error("file and content URIs are not supported; open web or app links only")
        }
        return withContext(Dispatchers.Main) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult("opened $raw")
            } catch (e: ActivityNotFoundException) {
                ToolResult.error("no app can open $raw")
            } catch (e: SecurityException) {
                ToolResult.error("not allowed to open $raw: ${e.message}")
            }
        }
    }
}
