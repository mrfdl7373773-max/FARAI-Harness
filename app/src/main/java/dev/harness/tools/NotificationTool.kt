package dev.harness.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.harness.R
import kotlinx.serialization.json.JsonObject

class NotificationTool : Tool {
    override val name = "notification_post"
    override val parameters: JsonObject = schema {
        string("title", required = true)
        string("body", required = true)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = ctx.android ?: return ToolResult.error("notifications are not available in this environment")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.error("notification permission not granted (Android Settings → Apps → FARAI Harness → Notifications)")
        }
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = launch?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(args.reqStr("title"))
            .setContentText(args.reqStr("body"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(args.reqStr("body")))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        return try {
            NotificationManagerCompat.from(context).notify(id, n)
            ToolResult("notification posted (id $id)")
        } catch (e: SecurityException) {
            ToolResult.error("notification blocked: ${e.message}")
        }
    }

    companion object {
        const val CHANNEL_ID = "farai_agent"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = context.getString(R.string.notification_channel_description) }
                )
            }
        }
    }
}
