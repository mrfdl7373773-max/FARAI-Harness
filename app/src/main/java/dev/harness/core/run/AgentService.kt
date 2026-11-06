package dev.harness.core.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.harness.HarnessApp
import dev.harness.MainActivity
import dev.harness.R

/**
 * Keeps the process alive while an agent run is in progress and the app is in the background.
 * Shows an ongoing notification with a Stop action; stops itself when no run is left.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as HarnessApp).container
        val runner = container.runner
        when (intent?.action) {
            ACTION_STOP_ALL -> runner.stopAll()
            ACTION_STOP_JOBS -> container.jobs.killAll()
        }
        val runs = runner.activeCount
        val jobs = container.jobs.allRunning()
        if (runs == 0 && jobs.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = ongoing(this, runs, intent?.getStringExtra(EXTRA_TITLE), jobs.size, jobs.firstOrNull()?.command)
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse has no daily time limit, so a localhost server can stay up for hours.
            val type = if (jobs.isNotEmpty()) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            runCatching { startForeground(ONGOING_ID, n, type) }
                .onFailure { startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, n)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "farai:agent").apply { setReferenceCounted(false) }
        }
        // Agent runs: 6 h cap. Servers: held until they are stopped (released in onDestroy).
        wakeLock?.let { if (jobs.isNotEmpty()) it.acquire() else it.acquire(6 * 60 * 60 * 1000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        const val CHANNEL_RUNNING = "farai_running"
        const val CHANNEL_DONE = "farai_done"
        private const val ONGOING_ID = 4201
        private const val ACTION_STOP_ALL = "dev.farai.harness.STOP_ALL"
        private const val ACTION_STOP_JOBS = "dev.farai.harness.STOP_JOBS"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_TITLE = "title"
        const val EXTRA_SESSION = "dev.farai.harness.SESSION"

        /** Starts, updates or stops the service to match live runs and background jobs. */
        fun sync(context: Context, runs: Int, title: String?, jobs: Int = 0, jobCommand: String? = null) {
            val i = Intent(context, AgentService::class.java).putExtra(EXTRA_COUNT, runs + jobs).putExtra(EXTRA_TITLE, title)
            if (runs + jobs > 0) {
                runCatching { ContextCompat.startForegroundService(context, i) }
            } else {
                runCatching { context.startService(i) } // tells a running service to stop; no-op otherwise
                context.getSystemService(NotificationManager::class.java)?.cancel(ONGOING_ID)
            }
        }

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_RUNNING) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_RUNNING, "Agent running", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while FARAI works in the background"
                    setShowBadge(false)
                })
            }
            if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a background run finishes, fails, or needs your approval"
                })
            }
        }

        private fun openApp(context: Context, sessionId: String?, req: Int): PendingIntent {
            val i = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (sessionId != null) i.putExtra(EXTRA_SESSION, sessionId)
            return PendingIntent.getActivity(context, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun ongoing(context: Context, runs: Int, title: String?, jobs: Int, jobCommand: String?): Notification {
            val stop = PendingIntent.getService(
                context, 1, Intent(context, AgentService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopJobs = PendingIntent.getService(
                context, 2, Intent(context, AgentService::class.java).setAction(ACTION_STOP_JOBS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val serverLine = when {
                jobs == 1 -> "Server running: ${jobCommand?.take(60) ?: "background job"}"
                jobs > 1 -> "$jobs servers running"
                else -> null
            }
            val offline = runs > 0 && !dev.harness.net.Connectivity.isOnline
            val head = when {
                offline -> dev.harness.ui.i18n.tr("Waiting for internet")
                runs > 0 -> "FARAI is working"
                else -> "FARAI server is on"
            }
            val text = listOfNotNull(
                when {
                    offline -> dev.harness.ui.i18n.tr("Paused until you're back online, then it continues on its own.")
                    runs == 1 -> title?.let { "Working on \"$it\"" } ?: "Working on your task"
                    runs > 1 -> "$runs sessions running"
                    else -> null
                },
                serverLine,
            ).joinToString(" · ")
            return NotificationCompat.Builder(context, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(head)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply { if (runs > 0) setProgress(0, 0, true) }
                .setContentIntent(openApp(context, null, 0))
                .apply { if (runs > 0) addAction(0, "Stop agent", stop) }
                .apply { if (jobs > 0) addAction(0, if (jobs == 1) "Stop server" else "Stop servers", stopJobs) }
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /** One-off notification when a background run ends or waits for approval. */
        fun notifyBackground(context: Context, sessionId: String, title: String?, kind: AgentRunner.BackgroundEvent) {
            ensureChannels(context)
            val (head, body) = when (kind) {
                AgentRunner.BackgroundEvent.FINISHED -> "FARAI finished" to (title ?: "Your task is done")
                AgentRunner.BackgroundEvent.FAILED -> "FARAI stopped with an error" to (title ?: "Open the session to see what happened")
                AgentRunner.BackgroundEvent.NEEDS_APPROVAL -> "FARAI needs your approval" to ((title?.let { "$it · " } ?: "") + "Tap to review the action")
            }
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(head)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, sessionId, sessionId.hashCode()))
                .build()
            runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(sessionId.hashCode(), n) }
        }
    }
}
