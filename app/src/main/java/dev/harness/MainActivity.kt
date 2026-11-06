package dev.harness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.harness.data.settings.AppSettings
import dev.harness.data.settings.ThemeMode
import dev.harness.core.linux.StorageAccess
import dev.harness.ui.nav.HarnessNav
import dev.harness.ui.setup.SetupFlags
import dev.harness.ui.theme.HarnessTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Session to open, set when a background-run notification is tapped. */
    private val pendingSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(dev.harness.core.run.AgentService.EXTRA_SESSION)?.let { pendingSession.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val container = (application as HarnessApp).container
        intent?.getStringExtra(dev.harness.core.run.AgentService.EXTRA_SESSION)?.let { pendingSession.value = it }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("farai_flags", MODE_PRIVATE).getBoolean("asked_notifications", false)
        ) {
            getSharedPreferences("farai_flags", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        setContent {
            var ready by remember { mutableStateOf(false) }
            var firstRun by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                firstRun = withContext(Dispatchers.IO) { container.prompts.ensureInitialized() }
                ready = true
            }
            val settings by container.settings.settings.collectAsState(initial = null)
            val s = settings ?: AppSettings()
            val dark = when (s.ui.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            dev.harness.ui.i18n.AppLanguage.parse(s.ui.language).let { if (dev.harness.ui.i18n.I18n.lang != it) dev.harness.ui.i18n.I18n.lang = it }
            // Shown once per process start; rotation/recreate skips it.
            var launched by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            HarnessTheme(darkTheme = dark, dynamicColor = s.ui.dynamicColor, fontScale = s.ui.fontScale) {
                if (ready && settings != null && launched) {
                    // Decided once per launch so finishing onboarding never rebuilds the nav graph.
                    val startOnboarding = remember { firstRun || !s.onboardingComplete }
                    // New installs (and v1.0 upgrades) are asked for All files access once, first.
                    val askStorage = remember {
                        !StorageAccess.isGranted(this@MainActivity) && !SetupFlags.storagePrompted(this@MainActivity)
                    }
                    HarnessNav(container = container, startOnboarding = startOnboarding, askStorageFirst = askStorage, openSession = pendingSession)
                }
            }
            if (!launched) dev.harness.ui.splash.LaunchScreen(ready = ready && settings != null, dark = dark) { launched = true }
        }
    }
}
