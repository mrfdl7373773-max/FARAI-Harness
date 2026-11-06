package dev.harness.tools

import android.os.Build
import android.os.StatFs
import kotlinx.serialization.json.JsonObject
import java.util.Locale

class DeviceInfoTool : Tool {
    override val name = "device_info"
    override val readOnly = true
    override val parameters: JsonObject = schema { }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val dm = ctx.android?.resources?.displayMetrics
        val free = runCatching { StatFs(ctx.workspace.root.path).availableBytes }.getOrNull()
            ?: ctx.workspace.root.usableSpace
        val lines = listOf(
            "manufacturer" to (Build.MANUFACTURER ?: "unknown"),
            "brand" to (Build.BRAND ?: "unknown"),
            "model" to (Build.MODEL ?: "unknown"),
            "device" to (Build.DEVICE ?: "unknown"),
            "android_version" to (Build.VERSION.RELEASE ?: "unknown"),
            "api_level" to Build.VERSION.SDK_INT.toString(),
            "security_patch" to (Build.VERSION.SECURITY_PATCH ?: "unknown"),
            "supported_abis" to (Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown"),
            "locale" to Locale.getDefault().toLanguageTag(),
            "screen" to (dm?.let { "${it.widthPixels}x${it.heightPixels} px, density ${it.density} (${it.densityDpi} dpi)" } ?: "unknown"),
            "workspace" to ctx.workspace.root.path,
            "workspace_free" to "%.1f MB".format(free / 1_048_576.0),
            "jvm_processors" to Runtime.getRuntime().availableProcessors().toString(),
            "jvm_max_memory" to "%.0f MB".format(Runtime.getRuntime().maxMemory() / 1_048_576.0),
        )
        return ToolResult(lines.joinToString("\n") { "${it.first}: ${it.second}" })
    }
}
