package dev.harness.ui.sessions

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.harness.data.session.SessionEntity
import dev.harness.ui.chat.SheetHandle
import dev.harness.ui.components.Aurora
import dev.harness.ui.components.CircleButton
import dev.harness.ui.components.EmptyState
import dev.harness.ui.components.FaraiMotion
import dev.harness.ui.components.FieldRow
import dev.harness.ui.components.FilterPill
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.PulseDot
import dev.harness.ui.components.RowDivider
import dev.harness.ui.components.SearchField
import dev.harness.ui.components.Spark
import dev.harness.ui.components.floating
import dev.harness.ui.components.pressScale
import dev.harness.ui.shareText
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class Filter(val label: String) { ALL("All"), RUNNING("Running"), DONE("Done"), PLAN("Planning") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(vm: SessionsViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val c = Harness.colors
    val sessions by vm.sessions.collectAsState()
    val query by vm.query.collectAsState()
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    var selMenu by remember { mutableStateOf(false) }
    var confirmDeleteMany by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(selecting) { selected = emptySet() }
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Background runs live outside this screen: look every second and a half.
    var running by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(sessions) {
        while (true) { running = vm.runningIds(sessions.orEmpty().map { it.id }); delay(1500) }
    }

    val all = sessions.orEmpty()
    val filtered = all.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.RUNNING -> it.id in running
            Filter.DONE -> it.status == "FINISHED"
            Filter.PLAN -> dev.harness.core.agents.AgentStore.legacyName(it.mode) == "plan"
        }
    }
    val groups = remember(filtered, running) { group(filtered, running) }
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 } }

    Box(Modifier.fillMaxSize()) {
        Aurora(Modifier.fillMaxWidth().height(320.dp), intensity = 0.9f)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 22.dp, end = 14.dp, top = 18.dp).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sessions", style = Harness.type.largeTitle, color = c.label)
                        Text(
                            if (running.isNotEmpty()) "${running.size} running · ${all.size} total" else if (all.isEmpty()) "FARAI Harness" else "${all.size} sessions",
                            style = Harness.type.subhead, color = c.secondaryLabel,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)); SearchField(query, { vm.query.value = it }, tr("Search sessions")) }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filter.entries.forEach { f ->
                        FilterPill(
                            tr(f.label), filter == f, onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); filter = f },
                            count = when (f) { Filter.RUNNING -> running.size; else -> null },
                        )
                    }
                }
            }
            if (sessions != null && filtered.isEmpty()) {
                item {
                    EmptyState(
                        null,
                        when { query.isNotBlank() -> "No results"; filter != Filter.ALL -> "Nothing here"; else -> "No sessions yet" },
                        when { query.isNotBlank() -> "Nothing matches “$query”."; filter != Filter.ALL -> "No session is ${filter.label.lowercase()} right now."; else -> "Start one and tell FARAI what to build." },
                        Modifier.padding(top = 40.dp),
                        leading = { dev.harness.ui.components.FaraiLogo(48.dp) },
                    )
                }
            }
            groups.forEach { (title, list) ->
                val folded = title in collapsedGroups
                item(key = "h-$title") {
                    Row(
                        Modifier.fillMaxWidth().animateItemPlacement().padding(start = 22.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = Harness.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                        Spacer(Modifier.width(6.dp))
                        Text("${list.size}", style = Harness.type.numeric, color = c.tertiaryLabel, modifier = Modifier.weight(1f))
                        val rot by animateFloatAsState(if (folded) -90f else 0f, FaraiMotion.spatial(), label = "fold")
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { collapsedGroups = if (folded) collapsedGroups - title else collapsedGroups + title },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, if (folded) tr("Expand") else tr("Collapse"), tint = c.tertiaryLabel, modifier = Modifier.size(20.dp).rotate(rot)) }
                    }
                }
                if (!folded) itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                    val shape = when {
                        list.size == 1 -> RoundedCornerShape(22.dp)
                        i == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                        i == list.lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
                        else -> RoundedCornerShape(6.dp)
                    }
                    Column(Modifier.animateItemPlacement(FaraiMotion.spatial()).padding(horizontal = 16.dp).padding(bottom = 2.dp)) {
                        SessionCard(
                            s, live = s.id in running, shape = shape,
                            selecting = selecting,
                            checked = s.id in selected,
                            onClick = { if (selecting) selected = if (s.id in selected) selected - s.id else selected + s.id else onOpen(s.id) },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); selected = selected + s.id },
                        )
                    }
                }
            }
        }
        // Status-bar scrim once the list moves under it.
        val scrim by animateColorAsState(if (scrolled) c.bar else c.bar.copy(alpha = 0f), label = "scrim")
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(scrim))

        // Selection mode: the header becomes the action bar.
        AnimatedVisibility(
            selecting, modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 },
        ) {
            val chosen = all.filter { it.id in selected }
            val allPinned = chosen.isNotEmpty() && chosen.all { dev.harness.core.SessionManager.isPinned(it) }
            Row(
                Modifier.fillMaxWidth().background(c.bar).windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(Icons.Rounded.Close, tr("Cancel selection")) { selected = emptySet() }
                Spacer(Modifier.width(12.dp))
                Text("${selected.size} selected", style = Harness.type.title3, color = c.label, modifier = Modifier.weight(1f))
                CircleButton(Icons.Rounded.Delete, tr("Delete"), tint = c.red, container = c.red.copy(alpha = 0.10f)) { confirmDeleteMany = true }
                Spacer(Modifier.width(8.dp))
                Box {
                    CircleButton(Icons.Rounded.MoreVert, tr("More actions")) { selMenu = true }
                    androidx.compose.material3.DropdownMenu(selMenu, { selMenu = false }, modifier = Modifier.background(c.surface)) {
                        dev.harness.ui.chat.MenuRow(Icons.Rounded.PushPin, if (allPinned) tr("Unpin") else "Pin") {
                            vm.setPinned(selected, !allPinned); selMenu = false; selected = emptySet()
                        }
                        dev.harness.ui.chat.MenuRow(Icons.Rounded.SelectAll, tr("Select all")) { selected = filtered.map { it.id }.toSet(); selMenu = false }
                        if (chosen.size == 1) dev.harness.ui.chat.MenuRow(Icons.Rounded.DriveFileRenameOutline, "Rename") {
                            renaming = chosen.first(); selMenu = false; selected = emptySet()
                        }
                        dev.harness.ui.chat.MenuRow(Icons.Rounded.ContentCopy, tr("Duplicate")) { vm.duplicateMany(selected); selMenu = false; selected = emptySet() }
                        dev.harness.ui.chat.MenuRow(Icons.Rounded.IosShare, "Export transcript") {
                            val text = chosen.joinToString("\n\n---\n\n") { e -> var t = ""; vm.transcript(e) { t = it }; t }
                            shareText(context, if (chosen.size == 1) chosen.first().title else "${chosen.size} sessions", text)
                            selMenu = false; selected = emptySet()
                        }
                    }
                }
            }
        }

        // Floating bottom bar: settings, search focus, and the one big action.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp)
                .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0f), c.background.copy(alpha = 0.35f), c.background.copy(alpha = 0.6f)))),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.floating(CircleShape, c.isDark).clip(CircleShape).background(c.surface).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CircleButton(Icons.Rounded.Settings, tr("Settings"), container = Color.Transparent, size = 46.dp, onClick = onSettings)
            }
            Spacer(Modifier.width(10.dp))
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier.weight(1f).height(58.dp).pressScale(interaction, 0.96f).floating(CircleShape, c.isDark).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(c.accent, c.brandEnd)))
                    .clickable(interaction, null) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.create(onOpen) }
                    .padding(start = 8.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(tr("New session"), style = Harness.type.headline, color = Color.White, modifier = Modifier.weight(1f))
                if (running.isNotEmpty()) {
                    PulseDot(Color.White, size = 6.dp)
                    Text("${running.size} live", style = Harness.type.label, color = Color.White)
                }
            }
        }
    }

    if (confirmDeleteMany) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text(if (n == 1) "Delete this session?" else "Delete $n sessions?", style = Harness.type.title3, color = c.label) },
            text = { Text(tr("Their history is removed from this device. Workspace files stay."), style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { vm.deleteMany(selected); selected = emptySet(); confirmDeleteMany = false }) { Text(tr("Delete"), style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmDeleteMany = false }) { Text(tr("Cancel"), style = Harness.type.label, color = c.secondaryLabel) } },
        )
    }

    renaming?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text(tr("Rename session"), style = Harness.type.title3, color = c.label) },
            text = { Column(Modifier.clip(RoundedCornerShape(18.dp)).background(c.surfaceElevated)) { FieldRow(tr("Title"), title, { title = it }) } },
            confirmButton = { TextButton(onClick = { vm.rename(s.id, title); renaming = null }) { Text(tr("Save"), style = Harness.type.headline, color = c.accent) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(tr("Cancel"), style = Harness.type.label, color = c.secondaryLabel) } },
        )
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text(trf("Delete “{0}”?", s.title), style = Harness.type.title3, color = c.label) },
            text = { Text(tr("The session and its history are removed from this device."), style = Harness.type.subhead, color = c.secondaryLabel) },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); deleting = null }) { Text(tr("Delete"), style = Harness.type.headline, color = c.red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel"), style = Harness.type.label, color = c.secondaryLabel) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: SessionEntity, live: Boolean, shape: androidx.compose.ui.graphics.Shape, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Harness.colors
    val agent = dev.harness.core.agents.AgentStore.legacyName(s.mode)
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (checked) c.accentSoft else c.surface, label = "cardSel")
    Row(
        Modifier.fillMaxWidth().pressScale(interaction, 0.985f).clip(shape).background(bg)
            .combinedClickable(interaction, null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(selecting, enter = fadeIn() + androidx.compose.animation.expandHorizontally(), exit = fadeOut() + androidx.compose.animation.shrinkHorizontally()) {
            Row {
                val box by animateColorAsState(if (checked) c.accent else Color.Transparent, label = "check")
                Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(box)
                        .border(2.dp, if (checked) c.accent else c.tertiaryLabel, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(checked, enter = androidx.compose.animation.scaleIn() + fadeIn(), exit = androidx.compose.animation.scaleOut() + fadeOut()) {
                        Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
            Spark(20.dp, live = live, colors = if (live || s.status == "FINISHED") dev.harness.ui.components.sparkBrushColors() else listOf(c.tertiaryLabel, c.secondaryLabel, c.tertiaryLabel))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dev.harness.core.SessionManager.isPinned(s)) {
                    Icon(Icons.Rounded.PushPin, "Pinned", tint = c.accent, modifier = Modifier.size(14.dp).rotate(35f))
                    Spacer(Modifier.width(4.dp))
                }
                Text(s.title, style = Harness.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaBit(Icons.Rounded.AutoAwesome, agent.replaceFirstChar { it.uppercase() })
                Spacer(Modifier.width(10.dp))
                MetaBit(Icons.Rounded.Memory, s.model.substringAfterLast('/').ifBlank { "model" }, Modifier.weight(1f, fill = false))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            when {
                live -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(c.ember, size = 6.dp)
                    Text("Running", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.ember)
                }
                s.status == "FINISHED" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, null, tint = c.green, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Done", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.green)
                }
                else -> Text(relative(s.updatedAt), style = Harness.type.caption, color = c.tertiaryLabel)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.clip(CircleShape).background(c.surfaceElevated).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Text("${s.messageCount}", style = Harness.type.numeric, color = c.secondaryLabel)
            }
        }
    }
}

@Composable
private fun MetaBit(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    val c = Harness.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.tertiaryLabel, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = Harness.type.caption, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun group(list: List<SessionEntity>, running: Set<String>): List<Pair<String, List<SessionEntity>>> {
    val startOfToday = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val week = startOfToday - 6 * 86_400_000L
    val pinned = list.filter { dev.harness.core.SessionManager.isPinned(it) }
    val live = list.filter { it.id in running && it !in pinned }
    val rest = list.filter { it.id !in running && it !in pinned }
    return listOf(
        "Pinned" to pinned,
        "Running" to live,
        "Today" to rest.filter { it.updatedAt >= startOfToday },
        "This week" to rest.filter { it.updatedAt in week until startOfToday },
        "Earlier" to rest.filter { it.updatedAt < week },
    ).filter { it.second.isNotEmpty() }
}

private fun relative(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        d < 7 * 86_400_000L -> "${d / 86_400_000}d"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}
