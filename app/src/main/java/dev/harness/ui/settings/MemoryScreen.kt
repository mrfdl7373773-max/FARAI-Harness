package dev.harness.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.harness.AppContainer
import dev.harness.core.memory.MemoryException
import dev.harness.core.memory.MemoryFormat
import dev.harness.core.memory.MemoryStore
import dev.harness.core.memory.RecallStore
import dev.harness.ui.components.CircleButton
import dev.harness.ui.components.LargeTitle
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.NavBar
import dev.harness.ui.components.RowDivider
import dev.harness.ui.components.Section
import dev.harness.ui.components.SegmentedControl
import dev.harness.ui.components.SwitchRow
import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Memory: switches, what FARAI remembers (core, folders, project), and history. */
@Composable
fun MemoryScreen(container: AppContainer, vm: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Harness.colors
    val settings by vm.settings.collectAsState()
    val s = settings ?: return
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 60 } }
    val store = container.memoryStore
    var tick by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) {
        snapshot = withContext(Dispatchers.IO) {
            store.ensureSeeded()
            Snapshot(
                core = store.coreBlocks(),
                rootIndexChars = store.rootIndex()?.length ?: 0,
                files = store.allFiles().filter { !it.isRoot && it.scope == MemoryStore.Scope.AGENT },
                project = store.allFiles().filter { it.scope == MemoryStore.Scope.PROJECT },
                history = store.history(limit = 12),
                total = store.coreTotal(),
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavBar(tr("Memory"), collapsed = collapsed, backLabel = tr("Back"), onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 48.dp)) {
                LargeTitle(tr("Memory"), subtitle = tr("Long-term memory"))
                Section(footer = tr("Remember across sessions: core memory in every prompt, memory and recall tools")) {
                    SwitchRow(tr("Memory"), s.agent.memory, { v -> vm.update { it.copy(agent = it.agent.copy(memory = v)) } }, Icons.Rounded.Psychology, c.purple)
                }
                val steps = listOf(-1 to tr("Off"), 0 to "Compaction", 4 to "4", 6 to "6", 12 to "12")
                val idx = steps.indexOfFirst { it.first == s.agent.reflectEvery }.let { if (it < 0) 3 else it }
                Section(header = tr("Reflection"), footer = tr("Background review that updates memory") + ": " + when {
                    s.agent.reflectEvery > 0 -> trf("every {0} messages and before compaction", s.agent.reflectEvery)
                    s.agent.reflectEvery == 0 -> tr("only before compaction")
                    else -> tr("never")
                }) {
                    Column(Modifier.padding(16.dp)) {
                        SegmentedControl(steps.map { it.second }, idx, { i -> vm.update { it.copy(agent = it.agent.copy(reflectEvery = steps[i].first)) } })
                    }
                }
                val snap = snapshot
                if (snap != null) {
                    Section(header = trf("Core · {0}/{1} chars", snap.total, store.limits.coreTotal), footer = tr("In every prompt. Tap to read or edit.")) {
                        snap.core.forEachIndexed { i, b ->
                            if (i > 0) RowDivider()
                            ListRow(
                                b.path,
                                icon = if (b.name == "human") Icons.Rounded.Person else Icons.Rounded.Psychology,
                                iconColor = if (b.chars > b.limit * 0.85) c.orange else c.accent,
                                subtitle = b.description.ifBlank { null },
                                value = "${b.chars * 100 / b.limit.coerceAtLeast(1)}%",
                                chevron = true,
                                onClick = { onOpen(b.path) },
                            )
                        }
                        if (snap.core.isNotEmpty()) RowDivider()
                        ListRow("MEMORY.md", icon = Icons.Rounded.Description, iconColor = c.teal, subtitle = tr("Index of everything else"), value = "${snap.rootIndexChars}", chevron = true, onClick = { onOpen(MemoryStore.INDEX) })
                    }
                    if (snap.files.isNotEmpty()) {
                        Section(header = tr("Folders"), footer = tr("Opened by FARAI when the subject comes up.")) {
                            snap.files.forEachIndexed { i, r ->
                                if (i > 0) RowDivider()
                                val desc = remember(r.display, tick) { runCatching { MemoryFormat.parse(r.file.readText()).description }.getOrNull() }
                                ListRow(r.display, icon = Icons.Rounded.Folder, iconColor = c.indigo, subtitle = desc, chevron = true, onClick = { onOpen(r.display) })
                            }
                        }
                    }
                    if (snap.project.isNotEmpty()) {
                        Section(header = tr("Project"), footer = tr("Stored in the workspace at .farai/memory.")) {
                            snap.project.forEachIndexed { i, r ->
                                if (i > 0) RowDivider()
                                ListRow(r.display, icon = Icons.Rounded.Folder, iconColor = c.green, chevron = true, onClick = { onOpen(r.display) })
                            }
                        }
                    }
                    Section(header = tr("History"), footer = tr("Every change FARAI or reflection made. Undo puts the file back.")) {
                        if (snap.history.isEmpty()) ListRow(tr("No changes yet"), icon = Icons.Rounded.History, iconColor = c.secondaryLabel)
                        snap.history.forEachIndexed { i, ch ->
                            if (i > 0) RowDivider()
                            ListRow(
                                "${ch.op} · ${ch.path}",
                                icon = Icons.Rounded.History,
                                iconColor = if (ch.source?.startsWith("reflection") == true) c.purple else c.secondaryLabel,
                                subtitle = listOfNotNull(RecallStore.fmt(ch.time), ch.reason?.take(120)).joinToString(" · "),
                                trailing = {
                                    CircleButton(Icons.Rounded.Undo, tr("Revert"), onClick = {
                                        scope.launch {
                                            message = withContext(Dispatchers.IO) { runCatching { store.revert(ch.id, "user") }.getOrElse { it.message } }
                                            tick++
                                        }
                                    })
                                },
                            )
                        }
                    }
                }
                message?.let { m -> Text(m, style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
            }
        }
    }
}

private data class Snapshot(
    val core: List<MemoryStore.Block>,
    val rootIndexChars: Int,
    val files: List<MemoryStore.Resolved>,
    val project: List<MemoryStore.Resolved>,
    val history: List<MemoryStore.Change>,
    val total: Int,
)

/** Plain-text editor for one memory file, with the same checks the agent's edits go through. */
@Composable
fun MemoryEditorScreen(container: AppContainer, path: String, onBack: () -> Unit) {
    val c = Harness.colors
    val store = container.memoryStore
    var text by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        val t = withContext(Dispatchers.IO) { runCatching { store.read(path) }.getOrElse { "" } }
        text = t; original = t
    }
    val dirty = text != null && text != original
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            NavBar(path, backLabel = tr("Back"), onBack = onBack, subtitle = text?.let { "${it.length} chars" }) {
                CircleButton(Icons.Rounded.Delete, tr("Delete"), onClick = { confirmDelete = true })
                Spacer(Modifier.width(8.dp))
                CircleButton(if (saved && !dirty) Icons.Rounded.Check else Icons.Rounded.Check, tr("Save"), onClick = {
                    val t = text ?: return@CircleButton
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { store.writeRaw(path, t, "edited by the user in Settings → Memory", "user") } }
                        r.onSuccess { original = t; saved = true; error = null }.onFailure { error = (it as? MemoryException)?.message ?: it.message }
                    }
                })
            }
            error?.let { e ->
                Text(e, style = Harness.type.footnote, color = c.red, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            if (saved && !dirty) Text(tr("Saved"), style = Harness.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.green, modifier = Modifier.padding(horizontal = 20.dp))
            val t = text
            if (t != null) {
                Box(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).verticalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = t,
                        onValueChange = { text = it; saved = false },
                        textStyle = Harness.type.mono.copy(color = c.label),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 200.dp),
                    )
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(trf("Delete {0}?", path)) },
        text = { Text(tr("It stays in history, so you can undo it.")) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { store.delete(path, "deleted by the user in Settings → Memory", "user") } }
                    r.onSuccess { onBack() }.onFailure { error = it.message }
                }
            }) { Text(tr("Delete"), color = c.red) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
    )
}
