package dev.harness.ui.settings

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.harness.BuildConfig
import dev.harness.data.settings.ThemeMode
import dev.harness.ui.components.BarTextButton
import dev.harness.ui.components.Hud
import dev.harness.ui.components.LargeTitle
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.NavBar
import dev.harness.ui.components.RowDivider
import dev.harness.ui.components.SearchField
import dev.harness.ui.components.Section
import dev.harness.ui.components.SegmentedControl
import dev.harness.ui.components.SwitchRow
import dev.harness.ui.components.TextAreaRow
import dev.harness.ui.theme.Harness

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onProvider: () -> Unit,
    onPrompts: () -> Unit,
    onSafety: () -> Unit,
    onLinux: () -> Unit = {},
    onMemory: () -> Unit = {},
) {
    val c = Harness.colors
    val settings by vm.settings.collectAsState()
    val message by vm.message.collectAsState()
    val s = settings ?: return
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 60 } }
    var confirmClear by remember { mutableStateOf(false) }

    val exportSessions = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportAllSessions(context, it) } }
    val exportConfig = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/toml")) { uri -> uri?.let { vm.exportConfig(context, it) } }
    val importConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importConfig(context, it) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavBar(tr("Settings"), collapsed = collapsed, backLabel = tr("Back"), onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 48.dp)) {
                LargeTitle(tr("Settings"))
                BrandCard(model = s.provider.model, onClick = onProvider)
                Section(header = "Model") {
                    ListRow("Provider", icon = Icons.Rounded.Cloud, iconColor = c.accent, value = s.provider.model, chevron = true, onClick = onProvider)
                }
                val linuxState by vm.linuxState.collectAsState()
                Section(header = "Environment", footer = tr("Ubuntu (recommended) or Alpine gives the agent python3, pip, git, node and more. Shared storage appears at /sdcard.")) {
                    ListRow("Linux", icon = Icons.Rounded.Terminal, iconColor = c.indigo, value = vm.linuxLabel(linuxState), chevron = true, onClick = onLinux)
                }
                Section(header = tr("Instructions"), footer = tr("Every word the model reads lives in these files. Edit, diff, reset or roll back any of them.")) {
                    ListRow("Prompts", icon = Icons.Rounded.Description, iconColor = c.purple, value = "${vm.prompts.collectAsState().value.size} files", chevron = true, onClick = onPrompts)
                }
                Section(header = tr("Memory"), footer = tr("Browse and edit what FARAI remembers")) {
                    ListRow(tr("Memory"), icon = Icons.Rounded.Psychology, iconColor = c.accent, value = if (s.agent.memory) tr("On") else tr("Off"), chevron = true, onClick = onMemory)
                }
                Section(header = "Agent") {
                    SwitchRow("Repo map", s.agent.repoMap, { v -> vm.update { it.copy(agent = it.agent.copy(repoMap = v)) } }, Icons.Rounded.Map, c.teal, tr("Index workspace files and symbols into the environment block"))
                    RowDivider()
                    SwitchRow("Compaction", s.agent.compaction, { v -> vm.update { it.copy(agent = it.agent.copy(compaction = v)) } }, Icons.Rounded.Compress, c.orange, tr("Summarise older turns with compaction.md"))
                    RowDivider()
                    SwitchRow("Subagents", s.agent.subagents, { v -> vm.update { it.copy(agent = it.agent.copy(subagents = v)) } }, Icons.Rounded.AccountTree, c.indigo, tr("Allow the task tool to delegate exploration"))
                    RowDivider()
                    SwitchRow("Auto planning", s.agent.autoPlanning, { v -> vm.update { it.copy(agent = it.agent.copy(autoPlanning = v)) } }, Icons.Rounded.Checklist, c.green, tr("Include planning.md for long or multi-step requests"))
                    RowDivider()
                    SliderRow(
                        title = tr("Compaction threshold"),
                        value = s.agent.contextThreshold,
                        range = 0.3f..0.95f,
                        label = trf("{0}% of context", (s.agent.contextThreshold * 100).toInt()),
                    ) { v -> vm.update { it.copy(agent = it.agent.copy(contextThreshold = (v * 100).toInt() / 100f)) } }
                }
                val modes = listOf("ask" to tr("Ask"), "edits" to "Auto-edit", "always" to tr("Always allow"))
                val modeIndex = modes.indexOfFirst { it.first == s.agent.approvalMode }.coerceAtLeast(0)
                Section(
                    header = "Permissions",
                    footer = when (modes[modeIndex].first) {
                        "edits" -> "File reads and edits go ahead on their own. Commands, web and other tools still ask."
                        "always" -> "Everything goes ahead without a question. Deny rules and Safety still block. Use with care."
                        else -> tr("FARAI asks before anything your rules mark as ask (commands, edits outside the workspace…).")
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("When the agent needs permission"), style = Harness.type.subhead, color = c.secondaryLabel)
                        Spacer(Modifier.height(10.dp))
                        SegmentedControl(modes.map { it.second }, modeIndex, { i ->
                            vm.update { it.copy(agent = it.agent.copy(approvalMode = modes[i].first)) }
                        })
                    }
                }
                Section(header = "Safety") {
                    ListRow(tr("Command rules"), icon = Icons.Rounded.Shield, iconColor = c.red,
                        value = trf("{0} blocked · {1} confirm", s.safety.blocklist.size, s.safety.confirmList.size), chevron = true, onClick = onSafety)
                }
                Section(header = tr("Appearance")) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("Theme"), style = Harness.type.subhead, color = c.secondaryLabel)
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(listOf(tr("System"), tr("Light"), tr("Dark")), s.ui.theme.ordinal, { i -> vm.update { it.copy(ui = it.ui.copy(theme = ThemeMode.entries[i])) } })
                    }
                    RowDivider(16.dp)
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("Language"), style = Harness.type.subhead, color = c.secondaryLabel)
                        Spacer(Modifier.height(8.dp))
                        val langs = dev.harness.ui.i18n.AppLanguage.entries
                        SegmentedControl(
                            langs.map { it.label },
                            langs.indexOf(dev.harness.ui.i18n.AppLanguage.parse(s.ui.language)),
                            { i -> vm.update { it.copy(ui = it.ui.copy(language = langs[i].code)) } },
                        )
                    }
                    RowDivider(16.dp)
                    SliderRow(tr("Text size"), s.ui.fontScale, 0.85f..1.3f, "${(s.ui.fontScale * 100).toInt()}%", Icons.Rounded.FormatSize) { v ->
                        vm.update { it.copy(ui = it.ui.copy(fontScale = (v * 20).toInt() / 20f)) }
                    }
                    RowDivider()
                    SwitchRow("Render markdown", s.ui.markdown, { v -> vm.update { it.copy(ui = it.ui.copy(markdown = v)) } }, Icons.Rounded.TextFields, c.gray)
                    RowDivider()
                    SwitchRow("Dynamic color", s.ui.dynamicColor, { v -> vm.update { it.copy(ui = it.ui.copy(dynamicColor = v)) } }, Icons.Rounded.Palette, c.pink, tr("Android 12+: tint accents from your wallpaper"))
                }
                Section(header = "Data") {
                    ListRow(tr("Export all sessions"), icon = Icons.Rounded.Archive, iconColor = c.green, onClick = { exportSessions.launch("farai-sessions.zip") })
                    RowDivider()
                    ListRow("Export config.toml", icon = Icons.Rounded.Upload, iconColor = c.accent, onClick = { exportConfig.launch("config.toml") })
                    RowDivider()
                    ListRow("Import config.toml", icon = Icons.Rounded.Download, iconColor = c.accent, onClick = { importConfig.launch(arrayOf("*/*")) })
                    RowDivider()
                    ListRow(tr("Clear session history"), icon = Icons.Rounded.DeleteForever, iconColor = c.red, titleColor = c.red, onClick = { confirmClear = true })
                }
                Section(footer = "Workspace: ${vm.workspacePath}") {
                    ListRow("FARAI Harness", icon = Icons.Rounded.AutoAwesome, iconColor = c.indigo, value = "v${BuildConfig.VERSION_NAME}")
                }
            }
        }
        Hud(message) { vm.consumeMessage() }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.surfaceElevated,
            title = { Text(tr("Delete all sessions?"), style = Harness.type.headline, color = c.label) },
            text = { Text(tr("Every saved session on this device will be removed. Prompts and settings stay."), style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text(tr("Delete All"), style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel"), color = c.accent) } },
        )
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Tune,
    onChange: (Float) -> Unit,
) {
    val c = Harness.colors
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            dev.harness.ui.components.IconTile(icon, c.gray)
            Spacer(Modifier.padding(start = 13.dp))
            Text(title, style = Harness.type.body, color = c.label, modifier = Modifier.weight(1f))
            Text(label, style = Harness.type.body, color = c.secondaryLabel)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = c.accent, inactiveTrackColor = c.fill),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderScreen(vm: SettingsViewModel, onBack: () -> Unit, onSaved: () -> Unit, saveLabel: String = tr("Save"), backLabel: String = tr("Settings")) {
    val c = Harness.colors
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val models by vm.models.collectAsState()
    val message by vm.message.collectAsState()
    val s = settings ?: return
    var draft by remember { mutableStateOf(ProviderDraft.of(s.provider)) }
    var picker by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavBar("Provider", collapsed = scroll.value > 60, backLabel = backLabel, onBack = onBack, actions = {
                BarTextButton(saveLabel, bold = true, enabled = draft.baseUrl.isNotBlank() && draft.model.isNotBlank()) {
                    vm.update { it.copy(provider = draft.apply(it.provider)) }
                    onSaved()
                }
            })
            Column(Modifier.fillMaxSize().verticalScroll(scroll).imePadding().padding(bottom = 48.dp)) {
                LargeTitle("Provider")
                ProviderForm(
                    draft = draft,
                    onChange = { draft = it },
                    connection = connection,
                    onTest = { vm.update { it.copy(provider = draft.apply(it.provider)) }; vm.testConnection() },
                    onPickModel = { vm.update { it.copy(provider = draft.apply(it.provider)) }; vm.testConnection(); picker = true },
                )
            }
        }
        Hud(message) { vm.consumeMessage() }
    }

    if (picker) {
        var q by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { picker = false }, containerColor = c.background) {
            Text(tr("Choose a model"), style = Harness.type.title3, color = c.label, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            SearchField(q, { q = it }, tr("Filter models"))
            Spacer(Modifier.height(10.dp))
            val filtered = models.filter { it.contains(q, true) }
            if (filtered.isEmpty()) {
                Text(
                    if (connection is ConnectionState.Testing) tr("Loading models…") else if (connection is ConnectionState.Failed) (connection as ConnectionState.Failed).message else tr("No models"),
                    style = Harness.type.subhead, color = c.secondaryLabel, modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().height(460.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(filtered) { m ->
                    Row(
                        Modifier.fillMaxWidth().background(c.surface).clickable { draft = draft.copy(model = m); picker = false }.padding(horizontal = 16.dp, vertical = 13.dp),
                    ) {
                        Text(m, style = Harness.type.mono, color = c.label, modifier = Modifier.weight(1f))
                        if (m == draft.model) Text("✓", style = Harness.type.headline, color = c.accent)
                    }
                    RowDivider(16.dp)
                }
            }
        }
    }
}

@Composable
fun SafetyScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val c = Harness.colors
    val settings by vm.settings.collectAsState()
    val s = settings ?: return
    var allow by remember { mutableStateOf(s.safety.allowlist.joinToString("\n")) }
    var block by remember { mutableStateOf(s.safety.blocklist.joinToString("\n")) }
    var confirm by remember { mutableStateOf(s.safety.confirmList.joinToString("\n")) }
    val scroll = rememberScrollState()
    fun lines(t: String) = t.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }

    Column(Modifier.fillMaxSize()) {
        NavBar("Safety", collapsed = scroll.value > 60, backLabel = tr("Settings"), onBack = onBack, actions = {
            BarTextButton(tr("Save"), bold = true) {
                vm.update { it.copy(safety = it.safety.copy(allowlist = lines(allow), blocklist = lines(block), confirmList = lines(confirm))) }
                onBack()
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(scroll).imePadding().padding(bottom = 48.dp)) {
            LargeTitle("Safety")
            Section(header = "Allowlist", footer = tr("One entry per line. When not empty, every command must start with an entry (e.g. ls, cat, grep). Empty means allow everything not blocked.")) {
                TextAreaRow(allow, { allow = it }, "ls\ncat\ngrep")
            }
            Section(header = "Blocklist", footer = tr("Commands containing any entry are refused. Wrap in /slashes/ for a regex. Use tool:<name> to block a whole tool.")) {
                TextAreaRow(block, { block = it }, "rm -rf /")
            }
            Section(header = tr("Require confirmation"), footer = tr("You'll be asked before commands containing these run. Use tool:write or tool:edit to approve every file change.")) {
                TextAreaRow(confirm, { confirm = it }, "rm \ntool:write")
            }
            Section(footer = tr("There is no hidden policy: empty all three lists to remove every restriction.")) {
                ListRow(tr("Restore default rules"), titleColor = c.accent, onClick = {
                    val d = dev.harness.data.settings.SafetySettings()
                    allow = d.allowlist.joinToString("\n"); block = d.blocklist.joinToString("\n"); confirm = d.confirmList.joinToString("\n")
                })
            }
        }
    }
}



/** The top card: the FARAI mark over a slow aurora, with the version and the model in use. */
@Composable
private fun BrandCard(model: String, onClick: () -> Unit) {
    val c = Harness.colors
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(26.dp))
            .background(c.accentSoft).clickable(onClick = onClick),
    ) {
        dev.harness.ui.components.Aurora(Modifier.matchParentSize(), intensity = 1.6f)
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                dev.harness.ui.components.FaraiLogo(40.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("FARAI Harness", style = Harness.type.title3, color = c.label)
                Text(
                    "v${dev.harness.BuildConfig.VERSION_NAME} · ${model.ifBlank { "no model yet" }}",
                    style = Harness.type.footnote, color = c.secondaryLabel, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
