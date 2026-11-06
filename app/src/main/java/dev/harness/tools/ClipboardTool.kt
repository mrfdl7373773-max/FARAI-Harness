package dev.harness.tools

import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class ClipboardTool : Tool {
    override val name = "clipboard_read"
    override val readOnly = true
    override val parameters: JsonObject = schema { }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = ctx.android ?: return ToolResult.error("clipboard is not available in this environment")
        return withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext ToolResult.error("clipboard service unavailable")
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) return@withContext ToolResult("(clipboard is empty, or Android denied access because the app is not in the foreground)")
            val text = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
                .joinToString("\n").ifEmpty { return@withContext ToolResult("(clipboard does not contain text)") }
            ToolResult(text)
        }
    }
}
