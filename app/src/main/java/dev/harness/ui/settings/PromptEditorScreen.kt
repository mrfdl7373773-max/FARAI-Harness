package dev.harness.ui.settings

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.harness.core.DiffKind
import dev.harness.ui.components.BarIcon
import dev.harness.ui.components.Hud
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.NavBar
import dev.harness.ui.components.PillButton
import dev.harness.ui.components.RowDivider
import dev.harness.ui.theme.Harness
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptEditorScreen(vm: SettingsViewModel, path: String, startInDiff: Boolean, onBack: () -> Unit) {
    val c = Harness.colors
    val state by vm.editor.collectAsState()
    val message by vm.message.collectAsState()
    var diffMode by remember { mutableStateOf(startInDiff) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var historySheet by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(path) { vm.openEditor(path) }
    LaunchedEffect(diffMode, state.text, state.loaded) { if (diffMode && state.loaded) vm.computeDiff() }

    val tryBack = { if (state.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavBar(
                title = (if (state.dirty) "● " else "") + path.substringAfterLast('/'),
                subtitle = path,
                backLabel = "Prompts",
                onBack = tryBack,
                actions = {
                    if (state.hasDefault) BarIcon(if (diffMode) Icons.Rounded.Edit else Icons.Rounded.CompareArrows, if (diffMode) "Edit" else "Diff", enabled = state.loaded) { diffMode = !diffMode }
                    BarIcon(Icons.Rounded.History, tr("History"), enabled = state.loaded) { historySheet = true }
                    if (state.hasDefault) BarIcon(Icons.Rounded.RestartAlt, "Reset") { confirmReset = true }
                    BarIcon(Icons.Rounded.Check, tr("Save"), enabled = state.dirty) { vm.save() }
                },
            )
            when {
                state.missing -> MissingFile(path, state.hasDefault) { vm.resetCurrent() }
                !state.loaded -> Unit
                diffMode -> DiffView(state.diff)
                else -> Editor(state.text) { vm.editText(it) }
            }
        }
        Hud(message) { vm.consumeMessage() }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = c.surfaceElevated,
            title = { Text(tr("Discard changes?"), style = Harness.type.headline, color = c.label) },
            text = { Text("Your unsaved edits to ${path.substringAfterLast('/')} will be lost.", style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text(tr("Discard"), style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(tr("Keep Editing"), color = c.accent) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = c.surfaceElevated,
            title = { Text(tr("Reset to default?"), style = Harness.type.headline, color = c.label) },
            text = { Text(tr("The shipped version replaces the current text. The current text stays in history."), style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetCurrent() }) { Text("Reset", style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Cancel"), color = c.accent) } },
        )
    }
    if (historySheet) {
        ModalBottomSheet(onDismissRequest = { historySheet = false }, containerColor = c.background) {
            Text(tr("Version history"), style = Harness.type.title3, color = c.label, modifier = Modifier.padding(horizontal = 20.dp))
            Text(trf("Last {0} saves (up to 50 kept)", state.history.size), style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            if (state.history.isEmpty()) Text(tr("No saved versions yet."), style = Harness.type.subhead, color = c.secondaryLabel, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))) {
                items(state.history) { h ->
                    Column(Modifier.background(c.surface)) {
                        ListRow(
                            title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(h.timestamp)),
                            subtitle = "${h.sizeBytes} bytes · ${h.id}",
                            chevron = true,
                            onClick = { preview = h.id to (vm.previewVersion(h.id) ?: "") },
                        )
                        RowDivider(16.dp)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
    preview?.let { (id, text) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            containerColor = c.surfaceElevated,
            title = { Text(trf("Version {0}", id), style = Harness.type.headline, color = c.label) },
            text = {
                Box(Modifier.height(320.dp).clip(RoundedCornerShape(10.dp)).background(c.codeBackground).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(text, style = Harness.type.monoSmall, color = c.label)
                }
            },
            confirmButton = { TextButton(onClick = { vm.rollback(id); preview = null; historySheet = false }) { Text(tr("Roll Back"), style = Harness.type.headline, color = c.accent) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(tr("Close"), color = c.accent) } },
        )
    }
}

@Composable
private fun Editor(text: String, onChange: (String) -> Unit) {
    val c = Harness.colors
    val vScroll = rememberScrollState()
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    Row(Modifier.fillMaxSize().imePadding().verticalScroll(vScroll).background(c.surface)) {
        // Gutter with line numbers, small and dim.
        Column(Modifier.background(c.background.copy(alpha = 0.5f)).padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 200.dp)) {
            for (i in 1..lines) Text(i.toString(), style = Harness.type.mono, color = c.tertiaryLabel, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = Harness.type.mono.copy(color = c.label),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
                modifier = Modifier.padding(start = 10.dp, end = 16.dp, top = 12.dp, bottom = 200.dp),
            )
        }
    }
}

@Composable
private fun DiffView(rows: List<dev.harness.core.DiffRow>) {
    val c = Harness.colors
    val changed = rows.count { it.kind != DiffKind.SAME }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(c.surfaceElevated).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Default", style = Harness.type.caption, color = c.secondaryLabel, modifier = Modifier.weight(1f))
            Text(trf("Current · {0} changed lines", changed), style = Harness.type.caption, color = c.secondaryLabel, modifier = Modifier.weight(1f))
        }
        if (changed == 0) {
            Text(tr("Identical to the shipped default."), style = Harness.type.subhead, color = c.secondaryLabel, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize().background(c.surface)) {
            items(rows) { r ->
                Row(Modifier.fillMaxWidth()) {
                    DiffCell(r.leftNo, r.left, if (r.kind == DiffKind.REMOVED || r.kind == DiffKind.CHANGED) c.red else null, Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.separator))
                    DiffCell(r.rightNo, r.right, if (r.kind == DiffKind.ADDED || r.kind == DiffKind.CHANGED) c.green else null, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DiffCell(no: Int?, text: String?, tint: androidx.compose.ui.graphics.Color?, modifier: Modifier) {
    val c = Harness.colors
    Row(modifier.background(tint?.copy(alpha = 0.14f) ?: androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 1.dp)) {
        Text(no?.toString() ?: "", style = Harness.type.monoSmall, color = c.tertiaryLabel, textAlign = TextAlign.End, modifier = Modifier.width(26.dp))
        Spacer(Modifier.width(6.dp))
        Text(text ?: "", style = Harness.type.monoSmall, color = if (text == null) c.tertiaryLabel else c.label)
    }
}

@Composable
private fun MissingFile(path: String, hasDefault: Boolean, onRestore: () -> Unit) {
    val c = Harness.colors
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.red.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Warning, null, tint = c.red, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("Prompt file missing"), style = Harness.type.title3, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text(trf("{0} doesn't exist on this device, so requests that need it will fail until it's restored.", path), style = Harness.type.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (hasDefault) PillButton(tr("Restore from defaults"), onClick = onRestore)
    }
}
