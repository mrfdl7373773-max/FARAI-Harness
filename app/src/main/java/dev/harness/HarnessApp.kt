package dev.harness

import android.app.Application
import dev.harness.tools.NotificationTool

class HarnessApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        container = AppContainer(this)
        NotificationTool.ensureChannel(this)
    }

    /** Writes any crash to filesDir/crash/ (and the workspace's .farai/crash/) so it can be read and fixed. */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                val text = "FARAI Harness ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nthread: ${t.name}\n\n" + android.util.Log.getStackTraceString(e)
                listOf(java.io.File(filesDir, "crash"), java.io.File(filesDir, "workspace/.farai/crash")).forEach { d ->
                    runCatching { d.mkdirs(); java.io.File(d, "crash-$stamp.txt").writeText(text) }
                }
            }
            previous?.uncaughtException(t, e)
        }
    }
}
