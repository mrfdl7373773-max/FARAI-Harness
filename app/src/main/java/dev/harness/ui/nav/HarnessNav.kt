package dev.harness.ui.nav

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.harness.AppContainer
import dev.harness.core.commands.NavTarget
import dev.harness.ui.chat.ChatScreen
import dev.harness.ui.chat.ChatViewModel
import dev.harness.ui.onboarding.OnboardingScreen
import dev.harness.ui.setup.LinuxSetupScreen
import dev.harness.ui.setup.StorageAccessScreen
import dev.harness.ui.sessions.SessionsScreen
import dev.harness.ui.sessions.SessionsViewModel
import dev.harness.ui.settings.PromptEditorScreen
import dev.harness.ui.settings.MemoryScreen
import dev.harness.ui.settings.MemoryEditorScreen
import dev.harness.ui.settings.PromptsScreen
import dev.harness.ui.settings.ProviderScreen
import dev.harness.ui.settings.SafetyScreen
import dev.harness.ui.settings.SettingsScreen
import dev.harness.ui.settings.SettingsViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val SETUP = "setup"
    const val STORAGE = "onboarding/storage"
    const val LINUX_SETUP = "onboarding/linux"
    const val LINUX = "settings/linux"
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{id}"
    const val SETTINGS = "settings"
    const val PROVIDER = "settings/provider"
    const val SAFETY = "settings/safety"
    const val PROMPTS = "settings/prompts"
    const val MEMORY = "settings/memory"
    const val MEMORY_EDIT = "settings/memory/edit?path={path}"
    fun memoryEdit(path: String) = "settings/memory/edit?path=${Uri.encode(path)}"
    const val EDITOR = "settings/prompts/edit?path={path}&diff={diff}"

    const val FILE = "file?path={path}"
    fun file(path: String) = "file?path=${Uri.encode(path)}"
    fun chat(id: String) = "chat/$id"
    fun editor(path: String, diff: Boolean = false) = "settings/prompts/edit?path=${Uri.encode(path)}&diff=$diff"
}

private const val DURATION = 320

@Composable
fun HarnessNav(
    container: AppContainer,
    startOnboarding: Boolean,
    askStorageFirst: Boolean = false,
    openSession: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
) {
    val nav = rememberNavController()
    if (openSession != null && !startOnboarding && !askStorageFirst) {
        val target by openSession.collectAsState()
        LaunchedEffect(target) {
            val id = target ?: return@LaunchedEffect
            openSession.value = null
            val here = nav.currentBackStackEntry?.arguments?.getString("id")
            if (here != id) nav.navigate(Routes.chat(id)) { popUpTo(Routes.SESSIONS) }
        }
    }
    val factory = remember {
        viewModelFactory {
            initializer { SettingsViewModel(container) }
            initializer { SessionsViewModel(container) }
        }
    }
    // One SettingsViewModel shared by every settings screen, scoped to the activity's NavHost owner.
    val settingsVm: SettingsViewModel = viewModel(factory = factory)

    NavHost(
        navController = nav,
        startDestination = when {
            askStorageFirst -> Routes.STORAGE
            startOnboarding -> Routes.ONBOARDING
            else -> Routes.SESSIONS
        },
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DURATION)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DURATION), targetOffset = { it / 4 }) + fadeOut(tween(DURATION)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DURATION), initialOffset = { it / 4 }) + fadeIn(tween(DURATION)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DURATION)) },
    ) {
        composable(Routes.STORAGE) {
            StorageAccessScreen(onContinue = {
                val next = if (startOnboarding) Routes.ONBOARDING else Routes.SESSIONS
                if (nav.currentDestination?.route == Routes.STORAGE) {
                    nav.navigate(next) { popUpTo(Routes.STORAGE) { inclusive = true } }
                }
            })
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(settingsVm) { nav.navigate(Routes.LINUX_SETUP) }
        }
        composable(Routes.LINUX_SETUP) {
            LinuxSetupScreen(container, onContinue = { nav.navigate(Routes.SETUP) })
        }
        composable(Routes.LINUX) { LinuxSetupScreen(container, onBack = { nav.popBackStack() }) }
        composable(Routes.SETUP) {
            ProviderScreen(settingsVm, onBack = { nav.popBackStack() }, saveLabel = "Done", backLabel = "Linux", onSaved = {
                settingsVm.completeOnboarding()
                nav.navigate(Routes.SESSIONS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.SESSIONS) {
            val vm: SessionsViewModel = viewModel(factory = factory)
            SessionsScreen(vm, onOpen = { nav.navigate(Routes.chat(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: ChatViewModel = viewModel(key = "chat-$id", factory = viewModelFactory { initializer { ChatViewModel(container, id) } })
            ChatScreen(vm, onBack = { nav.popBackStack(Routes.SESSIONS, inclusive = false) }, onNavigate = { nav.go(it) })
        }
        composable(Routes.FILE, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            dev.harness.ui.files.FileViewerScreen(
                path = path,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.file(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsVm,
                onBack = { nav.popBackStack() },
                onProvider = { nav.navigate(Routes.PROVIDER) },
                onPrompts = { nav.navigate(Routes.PROMPTS) },
                onSafety = { nav.navigate(Routes.SAFETY) },
                onLinux = { nav.navigate(Routes.LINUX) },
                onMemory = { nav.navigate(Routes.MEMORY) },
            )
        }
        composable(Routes.MEMORY) { MemoryScreen(container, settingsVm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.memoryEdit(it)) }) }
        composable(Routes.MEMORY_EDIT, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { entry ->
            MemoryEditorScreen(container, entry.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.PROVIDER) { ProviderScreen(settingsVm, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() }) }
        composable(Routes.SAFETY) { SafetyScreen(settingsVm, onBack = { nav.popBackStack() }) }
        composable(Routes.PROMPTS) { PromptsScreen(settingsVm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.editor(it)) }) }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "identity.md" },
                navArgument("diff") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            PromptEditorScreen(
                settingsVm,
                path = entry.arguments?.getString("path") ?: "identity.md",
                startInDiff = entry.arguments?.getBoolean("diff") ?: false,
                onBack = { nav.popBackStack() },
            )
        }
    }
    // MCP servers can ask for input (forms, pages, sampling approval) from any screen.
    dev.harness.ui.chat.McpInteractionHost(container.mcp)
}

private fun NavHostController.go(t: NavTarget) {
    when (t) {
        NavTarget.Sessions -> popBackStack(Routes.SESSIONS, inclusive = false)
        NavTarget.Settings -> navigate(Routes.SETTINGS)
        NavTarget.Provider -> navigate(Routes.PROVIDER)
        NavTarget.Linux -> navigate(Routes.LINUX)
        NavTarget.Prompts -> navigate(Routes.PROMPTS)
        is NavTarget.PromptEditor -> navigate(Routes.editor(t.path, t.showDiff))
        is NavTarget.Session -> navigate(Routes.chat(t.id)) { popUpTo(Routes.SESSIONS) }
        is NavTarget.OpenFile -> navigate(Routes.file(t.path))
    }
}
