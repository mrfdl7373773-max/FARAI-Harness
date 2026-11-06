package dev.harness.ui.settings

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.harness.data.prompt.PromptFile
import dev.harness.data.prompt.PromptGroup
import dev.harness.ui.components.BarIcon
import dev.harness.ui.components.Capsule
import dev.harness.ui.components.FieldRow
import dev.harness.ui.components.Hud
import dev.harness.ui.components.LargeTitle
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.NavBar
import dev.harness.ui.components.RowDivider
import dev.harness.ui.components.Section
import dev.harness.ui.theme.Harness
import dev.harness.ui.theme.HarnessColors
import java.text.DateFormat
import java.util.Date

private fun groupStyle(g: PromptGroup, c: HarnessColors) = when (g) {
    PromptGroup.IDENTITY -> Icons.Rounded.Person to c.purple
    PromptGroup.SYSTEM -> Icons.Rounded.Psychology to c.indigo
    PromptGroup.TOOLS -> Icons.Rounded.Build to c.gray
    PromptGroup.AGENTS -> Icons.Rounded.Psychology to c.purple
    PromptGroup.PERMISSIONS -> Icons.Rounded.Checklist to c.red
    PromptGroup.MODELS -> Icons.Rounded.Description to c.teal
    PromptGroup.BEHAVIOR -> Icons.Rounded.AccountTree to c.orange
    PromptGroup.TEMPLATES -> Icons.Rounded.Checklist to c.green
    PromptGroup.ONBOARDING -> Icons.Rounded.RocketLaunch to c.teal
    PromptGroup.OTHER -> Icons.Rounded.Description to c.accent
}

@Composable
fun PromptsScreen(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Harness.colors
    val prompts by vm.prompts.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var menu by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var resetOne by remember { mutableStateOf<PromptFile?>(null) }
    var importTarget by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val importMd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = importTarget
        if (uri != null && t != null) vm.importMarkdown(context, uri, t)
        importTarget = null
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importZip(context, it) } }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportZip(context, it) } }
    var pickImportFor by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavBar("Prompts", collapsed = scroll.value > 60, backLabel = tr("Settings"), onBack = onBack, actions = {
                BarIcon(Icons.Rounded.Add, tr("New prompt")) { creating = true }
                Box {
                    BarIcon(Icons.Rounded.MoreHoriz, tr("More")) { menu = true }
                    DropdownMenu(menu, { menu = false }, Modifier.background(c.surfaceElevated)) {
                        DropdownMenuItem(text = { Text(tr("Import .md into…")) }, onClick = { menu = false; pickImportFor = true })
                        DropdownMenuItem(text = { Text("Load prompt set (.zip)") }, onClick = { menu = false; importZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
                        DropdownMenuItem(text = { Text(tr("Export all (.zip)")) }, onClick = { menu = false; exportZip.launch("farai-prompts.zip") })
                        HorizontalDivider(color = c.separator)
                        DropdownMenuItem(text = { Text(tr("Reset all to defaults"), color = c.red) }, onClick = { menu = false; confirmResetAll = true })
                    }
                }
            })
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 48.dp)) {
                LargeTitle("Prompts")
                Text(
                    "This is everything the model is told. Changes apply to the very next message.",
                    style = Harness.type.subhead, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 20.dp),
                )
                prompts.groupBy { it.group }.toSortedMap(compareBy { it.order }).forEach { (group, files) ->
                    Section(header = group.label + if (group == PromptGroup.TOOLS) " · ${files.size}" else "") {
                        files.forEachIndexed { i, f ->
                            val (icon, color) = groupStyle(group, c)
                            ListRow(
                                title = f.path.removePrefix("tools/").removePrefix("templates/"),
                                subtitle = "${f.path} · ${f.sizeBytes} B · " + if (f.lastModified > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.lastModified)) else "—",
                                icon = if (!f.exists) Icons.Rounded.Warning else icon,
                                iconColor = if (!f.exists) c.red else color,
                                chevron = true,
                                onClick = { onOpen(f.path) },
                                onLongClick = { if (f.hasDefault) resetOne = f },
                                trailing = {
                                    when {
                                        !f.exists -> Capsule("missing", c.red)
                                        !f.hasDefault -> Capsule("custom", c.accent)
                                        f.modified -> Capsule("edited", c.orange)
                                    }
                                },
                            )
                            if (i < files.lastIndex) RowDivider()
                        }
                    }
                }
                Text(
                    "Long-press a file to reset it. Add a tools/<name>.md to describe a custom tool, or a templates/<name>.md for /template.",
                    style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                )
            }
        }
        Hud(message) { vm.consumeMessage() }
    }

    if (pickImportFor) {
        AlertDialog(
            onDismissRequest = { pickImportFor = false },
            containerColor = c.surfaceElevated,
            title = { Text(tr("Import into which file?"), style = Harness.type.headline, color = c.label) },
            text = {
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface).verticalScroll(rememberScrollState())) {
                    prompts.forEachIndexed { i, f ->
                        ListRow(f.path, onClick = {
                            pickImportFor = false; importTarget = f.path
                            importMd.launch(arrayOf("text/markdown", "text/plain", "text/*", "*/*"))
                        })
                        if (i < prompts.lastIndex) RowDivider(16.dp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickImportFor = false }) { Text(tr("Cancel"), color = c.accent) } },
        )
    }

    if (creating) {
        var path by remember { mutableStateOf("templates/") }
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = c.surfaceElevated,
            title = { Text(tr("New prompt file"), style = Harness.type.headline, color = c.label) },
            text = { Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface)) { FieldRow("Path", path, { path = it }, "templates/deploy.md") } },
            confirmButton = { TextButton(onClick = { vm.createPrompt(path); creating = false }) { Text(tr("Create"), style = Harness.type.headline, color = c.accent) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(tr("Cancel"), color = c.accent) } },
        )
    }

    resetOne?.let { f ->
        AlertDialog(
            onDismissRequest = { resetOne = null },
            containerColor = c.surfaceElevated,
            title = { Text("Reset ${f.name}?", style = Harness.type.headline, color = c.label) },
            text = { Text(tr("Restores the shipped default. Your current text is kept in this file's history."), style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { vm.resetPrompt(f.path); resetOne = null }) { Text("Reset", style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { resetOne = null }) { Text(tr("Cancel"), color = c.accent) } },
        )
    }

    if (confirmResetAll) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            containerColor = c.surfaceElevated,
            title = { Text(tr("Reset every prompt?"), style = Harness.type.headline, color = c.red) },
            text = {
                Column {
                    Text(tr("All shipped prompt files go back to their defaults. Custom files you created stay. Type RESET to confirm."), style = Harness.type.subhead, color = c.secondaryLabel)
                    Column(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).background(c.surface)) { FieldRow(tr("Confirm"), typed, { typed = it }, "RESET") }
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim() == "RESET", onClick = { vm.resetAllPrompts(); confirmResetAll = false }) {
                    Text("Reset All", style = Harness.type.headline, color = if (typed.trim() == "RESET") c.red else c.tertiaryLabel)
                }
            },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text(tr("Cancel"), color = c.accent) } },
        )
    }
}

