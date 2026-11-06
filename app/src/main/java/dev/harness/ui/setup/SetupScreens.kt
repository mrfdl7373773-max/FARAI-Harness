package dev.harness.ui.setup

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.mutableStateMapOf

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.harness.AppContainer
import dev.harness.core.linux.LinuxRuntime
import dev.harness.core.linux.LinuxState
import dev.harness.core.linux.StorageAccess
import dev.harness.ui.components.LargeTitle
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.NavBar
import dev.harness.ui.components.PillButton
import dev.harness.ui.components.RowDivider
import dev.harness.ui.components.Section
import dev.harness.ui.components.SwitchRow
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Remembers whether we already opened the All files access screen once on our own. */
object SetupFlags {
    private fun prefs(c: Context) = c.getSharedPreferences("farai_setup", Context.MODE_PRIVATE)
    fun storagePrompted(c: Context) = prefs(c).getBoolean("storage_prompted", false)
    fun markStoragePrompted(c: Context) = prefs(c).edit().putBoolean("storage_prompted", true).apply()
}

/** Re-reads the permission whenever the app comes back to the foreground. */
@Composable
fun rememberStorageGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.isGranted(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = StorageAccess.isGranted(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return granted
}

@Composable
private fun HeroIcon(icon: ImageVector, start: Color, end: Color) {
    Box(
        Modifier.size(96.dp).shadow(24.dp, RoundedCornerShape(24.dp), spotColor = end)
            .clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(52.dp)) }
}

@Composable
private fun Bullet(icon: ImageVector, tint: Color, title: String, text: String) {
    val c = Harness.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = Harness.type.headline, color = c.label)
            Text(text, style = Harness.type.subhead, color = c.secondaryLabel)
        }
    }
}

/**
 * First thing a new install sees: asks for All files access, and opens the system switch for
 * it automatically the first time so the user only has to flip it and come back.
 */
@Composable
fun StorageAccessScreen(onContinue: () -> Unit) {
    val c = Harness.colors
    val context = LocalContext.current
    val granted = rememberStorageGranted()
    var shown by remember { mutableStateOf(false) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onContinue() }

    fun request() {
        SetupFlags.markStoragePrompted(context)
        if (StorageAccess.needsSpecialAccess) runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
        else legacy.launch(StorageAccess.legacyPermissions)
    }

    LaunchedEffect(Unit) {
        shown = true
        if (!granted && !SetupFlags.storagePrompted(context)) {
            delay(900) // let the screen explain itself before the system page slides in
            request()
        }
    }
    LaunchedEffect(granted) { if (granted) { delay(450); onContinue() } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(360.dp).background(Brush.verticalGradient(listOf(c.teal.copy(alpha = 0.30f), c.accent.copy(alpha = 0.08f), Color.Transparent))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(48.dp))
                AnimatedVisibility(shown, enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 4 }) {
                    HeroIcon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.FolderOpen, c.teal, c.accent)
                }
                Spacer(Modifier.height(28.dp))
                Text(if (granted) tr("Access granted") else tr("Allow access to your files"), style = Harness.type.largeTitle, color = c.label)
                Spacer(Modifier.height(8.dp))
                Text(
                    "FARAI Harness works on real projects. With All files access, the agent and its Linux shell can open, edit and create files anywhere in your storage, not only inside the app.",
                    style = Harness.type.body, color = c.secondaryLabel,
                )
                Spacer(Modifier.height(20.dp))
                Bullet(Icons.Rounded.Folder, c.accent, tr("Edit local projects"), tr("Point the agent at /sdcard/Projects or your Downloads."))
                Bullet(Icons.Rounded.Terminal, c.green, tr("Linux sees them too"), tr("Shared storage is mounted at /sdcard inside Alpine."))
                Bullet(Icons.Rounded.CheckCircle, c.orange, tr("You stay in control"), tr("Edits still go through the safety rules, and you can revoke access in Android Settings."))
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                if (granted) PillButton(tr("Continue"), color = c.green, onClick = onContinue)
                else PillButton(if (StorageAccess.needsSpecialAccess) tr("Open Settings") else tr("Allow Access"), onClick = { request() })
                Spacer(Modifier.height(4.dp))
                if (!granted) TextButton(onClick = onContinue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(tr("Not now"), style = Harness.type.body, color = c.accent)
                }
                if (StorageAccess.needsSpecialAccess && !granted) Text(
                    "Turn on “Allow access to manage all files”, then come back.",
                    style = Harness.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Shared by onboarding (with [onContinue]) and Settings → Linux (with [onBack]). */
@Composable
fun LinuxSetupScreen(container: AppContainer, onBack: (() -> Unit)? = null, onContinue: (() -> Unit)? = null) {
    val c = Harness.colors
    val linux = container.linux
    val states by linux.states.collectAsState()
    val active by linux.active.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = null)
    val storageGranted = rememberStorageGranted()
    val context = LocalContext.current
    var devTools by remember { mutableStateOf(true) }
    var confirmRemove by remember { mutableStateOf<dev.harness.core.linux.Distro?>(null) }
    val scroll = rememberScrollState()
    val sizes = remember { mutableStateMapOf<dev.harness.core.linux.Distro, Long?>() }
    val ubuntuRelease by linux.ubuntuRelease.collectAsState()
    var confirmSwitch by remember { mutableStateOf<dev.harness.core.linux.UbuntuRelease?>(null) }

    LaunchedEffect(Unit) {
        linux.refresh()
        sizes[dev.harness.core.linux.Distro.ALPINE] = linux.downloadSize(dev.harness.core.linux.Distro.ALPINE)
    }
    LaunchedEffect(ubuntuRelease) {
        sizes[dev.harness.core.linux.Distro.UBUNTU] = null
        sizes[dev.harness.core.linux.Distro.UBUNTU] = linux.downloadSize(dev.harness.core.linux.Distro.UBUNTU)
    }
    fun pickUbuntu(r: dev.harness.core.linux.UbuntuRelease) {
        if (r == ubuntuRelease) return
        val onDisk = linux.installedUbuntuRelease()
        if (onDisk != null && onDisk != r) confirmSwitch = r else linux.setUbuntuRelease(r)
    }
    fun install(d: dev.harness.core.linux.Distro) {
        container.appScope.launch { linux.install(d, if (devTools) null else emptyList()) }
    }
    val anyReady = states.values.any { it is LinuxState.Ready }
    val unsupported = states.values.firstOrNull { it is LinuxState.Unsupported } as? LinuxState.Unsupported

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (onBack != null) NavBar("Linux", collapsed = scroll.value > 60, backLabel = "Settings", onBack = onBack)
            else Spacer(Modifier.statusBarsPadding().height(24.dp))
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                if (onBack != null) LargeTitle("Linux", subtitle = "Pick the environment the agent works in.") else Column(Modifier.padding(horizontal = 24.dp)) {
                    dev.harness.ui.components.FaraiLogo(64.dp)
                    Spacer(Modifier.height(22.dp))
                    Text(tr("A real Linux shell"), style = Harness.type.largeTitle, color = c.label)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android's own shell has no python, pip or git. FARAI runs a Linux distro in the app (PRoot, no root needed). Ubuntu is the most compatible; Alpine is the lightest.",
                        style = Harness.type.body, color = c.secondaryLabel,
                    )
                }
                if (unsupported != null) {
                    Section(header = "Status") { ListRow(tr("Not available"), subtitle = unsupported.reason, icon = Icons.Rounded.Terminal, iconColor = c.gray) }
                } else {
                    Text(
                        "Free space: ${formatBytes(linux.freeBytes())}",
                        style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 22.dp, top = 14.dp),
                    )
                    dev.harness.core.linux.Distro.entries.forEach { d ->
                        DistroCard(
                            distro = d,
                            state = states[d] ?: LinuxState.NotInstalled,
                            active = d == active,
                            downloadBytes = sizes[d],
                            arch = linux.arch,
                            onInstall = { install(d) },
                            onActivate = { linux.setActive(d) },
                            onRemove = { confirmRemove = d },
                            ubuntuRelease = ubuntuRelease,
                            installedUbuntu = linux.installedUbuntuRelease(),
                            onPickUbuntu = ::pickUbuntu,
                        )
                    }
                    Section(header = "Packages", footer = tr("Added on install. Add more later from chat, e.g. /linux add nodejs npm")) {
                        SwitchRow(
                            "Developer tools", devTools, { devTools = it }, Icons.Rounded.Terminal, c.green,
                            "python3, pip, git, curl, nano",
                        )
                    }
                }
                val s = settings
                if (onBack != null && s != null) {
                    Section(header = "Agent", footer = tr("Off: the bash tool uses Android's /system/bin/sh (toybox).")) {
                        SwitchRow(tr("Run bash in Linux"), s.agent.useLinux, { v ->
                            container.appScope.launch { container.settings.update { it.copy(agent = it.agent.copy(useLinux = v)) } }
                        }, Icons.Rounded.Terminal, c.indigo)
                    }
                    Section(header = tr("Storage"), footer = if (storageGranted) tr("Shared storage is mounted at /sdcard.") else tr("Without it, Linux and the file tools only see the workspace.")) {
                        ListRow(
                            "All files access", icon = Icons.Rounded.FolderOpen, iconColor = c.teal,
                            value = if (storageGranted) tr("Allowed") else tr("Off"), chevron = true,
                            onClick = { runCatching { context.startActivity(StorageAccess.settingsIntent(context)) } },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (onContinue != null) {
                val working = states.values.any { it is LinuxState.Working }
                Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    when {
                        anyReady || unsupported != null -> PillButton("Continue", onClick = onContinue)
                        working -> TextButton(onClick = onContinue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(tr("Continue in background"), style = Harness.type.body, color = c.accent)
                        }
                        else -> {
                            PillButton(tr("Install Ubuntu (recommended)"), onClick = { install(dev.harness.core.linux.Distro.UBUNTU) })
                            TextButton(onClick = onContinue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text(tr("Skip for now"), style = Harness.type.body, color = c.accent)
                            }
                        }
                    }
                }
            }
        }
    }

    confirmSwitch?.let { r ->
        val onDisk = linux.installedUbuntuRelease()
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text(trf("Switch to Ubuntu {0}?", r.label), style = Harness.type.title3, color = c.label) },
            text = {
                Text(
                    "Ubuntu ${onDisk?.label ?: ""} and every package installed in it are replaced by a fresh Ubuntu ${r.label}. Your workspace files stay.",
                    style = Harness.type.subhead, color = c.secondaryLabel,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitch = null
                    linux.setUbuntuRelease(r)
                    install(dev.harness.core.linux.Distro.UBUNTU)
                }) { Text(tr("Switch"), style = Harness.type.headline, color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text(tr("Cancel"), style = Harness.type.label, color = c.secondaryLabel) } },
        )
    }

    confirmRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Uninstall ${d.title}?", style = Harness.type.title3, color = c.label) },
            text = { Text(trf("{0} and every package installed in it are deleted. Your workspace files stay.", d.title), style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { confirmRemove = null; container.appScope.launch { linux.uninstall(d) } }) { Text("Uninstall", style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(tr("Cancel"), style = Harness.type.label, color = c.secondaryLabel) } },
        )
    }
}

/** One distro: what it is, what it costs, and its install state with the actions that fit. */
@Composable
private fun DistroCard(
    distro: dev.harness.core.linux.Distro,
    state: LinuxState,
    active: Boolean,
    downloadBytes: Long?,
    arch: String?,
    onInstall: () -> Unit,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
    ubuntuRelease: dev.harness.core.linux.UbuntuRelease = dev.harness.core.linux.UbuntuRelease.DEFAULT,
    installedUbuntu: dev.harness.core.linux.UbuntuRelease? = null,
    onPickUbuntu: (dev.harness.core.linux.UbuntuRelease) -> Unit = {},
) {
    val c = Harness.colors
    val ubuntu = distro == dev.harness.core.linux.Distro.UBUNTU
    val tint = if (ubuntu) c.ember else c.teal
    val shape = RoundedCornerShape(24.dp)
    val border by androidx.compose.animation.animateColorAsState(if (active && state is LinuxState.Ready) c.accent else c.codeBorder, label = "distroBorder")
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp).clip(shape).background(c.surface)
            .border(if (active && state is LinuxState.Ready) 1.5.dp else 1.dp, border, shape)
            .animateContentSize().padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Text(if (ubuntu) "U" else "A", style = Harness.type.title3, color = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(distro.title, style = Harness.type.title3, color = c.label)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(CircleShape).background(if (ubuntu) c.accent else c.green.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text(if (ubuntu) tr("Recommended") else tr("Lightweight"), style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (ubuntu) Color.White else c.green)
                    }
                }
                Text(if (ubuntu) "glibc · apt · ${ubuntuRelease.label}" else "musl · apk · 3.24", style = Harness.type.numeric, color = c.tertiaryLabel)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (ubuntu) tr("The most compatible Linux for an AI coding agent. Good for Python, Node.js, Git, build tools and CLI coding agents. About 30 to 35 MB to download.")
            else tr("The smallest and fastest to download. Good for a Linux terminal, Git, basic Python/Node.js and small to medium projects. The rootfs is only about 4 MB."),
            style = Harness.type.subhead, color = c.label,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (ubuntu) tr("Plan for 1 to 2 GB of free space once packages and project dependencies are installed.")
            else tr("Some packages or binaries built for glibc may need adjusting, because Alpine uses musl."),
            style = Harness.type.footnote, color = c.secondaryLabel,
        )
        if (ubuntu && state !is LinuxState.Working && state !is LinuxState.Unsupported) {
            Spacer(Modifier.height(12.dp))
            val releases = dev.harness.core.linux.UbuntuRelease.entries
            dev.harness.ui.components.SegmentedControl(
                options = releases.map { it.label },
                selected = releases.indexOf(ubuntuRelease).coerceAtLeast(0),
                onSelect = { onPickUbuntu(releases[it]) },
            )
            Spacer(Modifier.height(6.dp))
            Text(ubuntuRelease.note, style = Harness.type.footnote, color = c.secondaryLabel)
        }
        Spacer(Modifier.height(12.dp))
        when (state) {
            is LinuxState.Unsupported -> Text(state.reason, style = Harness.type.footnote, color = c.red)
            LinuxState.NotInstalled -> {
                Text(
                    "Download ${downloadBytes?.let { formatBytes(it) } ?: if (ubuntu) "≈30 MB" else "≈4 MB"} · ${arch ?: "?"} · SHA-256 verified",
                    style = Harness.type.footnote, color = c.secondaryLabel,
                )
                Spacer(Modifier.height(10.dp))
                val replacing = ubuntu && installedUbuntu != null && installedUbuntu != ubuntuRelease
                PillButton(
                    when {
                        replacing -> trf("Replace {0} with {1}", installedUbuntu?.label, ubuntuRelease.label)
                        ubuntu -> "Install Ubuntu ${ubuntuRelease.label}"
                        else -> "Install ${distro.title}"
                    },
                    color = if (ubuntu) c.accent else c.label.copy(alpha = 0.85f), onClick = onInstall)
            }
            is LinuxState.Working -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.harness.ui.components.Spark(16.dp, live = true)
                    Spacer(Modifier.width(8.dp))
                    Text(state.stage, style = Harness.type.subhead, color = c.label, modifier = Modifier.weight(1f))
                    state.progress?.let { Text("${(it * 100).toInt()}%", style = Harness.type.numeric, color = c.secondaryLabel) }
                }
                Spacer(Modifier.height(10.dp))
                ProgressBar(state.progress)
                if (state.log.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp))) { Terminal(state.log) } }
            }
            is LinuxState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(trf("Ready · {0} · {1} used", state.release, formatBytes(state.sizeBytes)), style = Harness.type.subhead.copy(fontWeight = FontWeight.Medium), color = c.label)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Row(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Active"), style = Harness.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                        }
                    } else dev.harness.ui.components.BarTextButton(tr("Use as default"), bold = true, onClick = onActivate)
                    dev.harness.ui.components.BarTextButton(tr("Repair"), onClick = onInstall)
                    Spacer(Modifier.weight(1f))
                    dev.harness.ui.components.CircleButton(Icons.Rounded.DeleteForever, "Uninstall ${distro.title}", tint = c.red, container = c.red.copy(alpha = 0.10f), size = 40.dp, onClick = onRemove)
                }
            }
            is LinuxState.Failed -> {
                Text(trf("Setup failed: {0}", state.message), style = Harness.type.footnote, color = c.red)
                if (state.log.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp))) { Terminal(state.log) } }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.harness.ui.components.BarTextButton(tr("Try again"), bold = true, onClick = onInstall)
                    dev.harness.ui.components.BarTextButton(tr("Remove"), color = c.red, onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float?) {
    val c = Harness.colors
    val p by animateFloatAsState(progress ?: 0f, tween(250), label = "progress")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.fill)) {
        if (progress != null) Box(Modifier.fillMaxWidth(p).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(c.brandStart, c.brandEnd))))
        else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = c.accent, trackColor = c.fill)
    }
}

@Composable
private fun Terminal(lines: List<String>) {
    val c = Harness.colors
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.lastIndex) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).background(c.codeBackground).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(lines) { Text(it, style = Harness.type.monoSmall, color = c.secondaryLabel) }
    }
}

fun formatBytes(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "%.0f MB".format(b / 1048576.0)
    else -> "%.1f GB".format(b / 1073741824.0)
}
