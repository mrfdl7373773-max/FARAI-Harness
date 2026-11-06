package dev.harness.ui.chat

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.harness.tools.TodoItem
import dev.harness.core.commands.NavTarget
import dev.harness.ui.components.Aurora
import dev.harness.ui.components.CircleButton
import dev.harness.ui.components.FaraiMotion
import dev.harness.ui.components.NavBar
import dev.harness.ui.components.Spark
import dev.harness.ui.components.floating
import dev.harness.ui.components.pressScale
import dev.harness.ui.shareText
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onNavigate: (NavTarget) -> Unit,
) {
    val c = Harness.colors
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    val todos by vm.todos.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var menu by remember { mutableStateOf(false) }
    var todoSheet by remember { mutableStateOf(false) }
    var runSheet by remember { mutableStateOf(false) }
    var expandedCompactions by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    var runStart by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf(false) }
    val starters = rememberSaveable { Starters.pick(4) }
    LaunchedEffect(ui.running) { runStart = if (ui.running) System.currentTimeMillis() else null }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ChatEvent.Navigate -> onNavigate(e.target)
                is ChatEvent.Share -> shareText(context, e.title, e.text)
            }
        }
    }

    val rows = remember(ui.items) { transcriptRows(ui.items) }
    val lastUserKey = remember(ui.items) { ui.items.lastOrNull { it is ChatItem.User }?.key }
    val showWorking = ui.running && ui.streaming.isEmpty()
    val totalRows = rows.size + (if (ui.streaming.isNotEmpty()) 1 else 0) + (if (showWorking) 1 else 0)
    // Follow the bottom while the user is there; leave them be once they scroll up.
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) follow = atBottom }
    LaunchedEffect(totalRows, ui.streaming.length / 60) {
        if (totalRows > 0 && follow) listState.animateScrollToItem(totalRows - 1, Int.MAX_VALUE / 2)
    }
    val agentDef = ui.agents.firstOrNull { it.name.equals(ui.agent, true) }

    val headerSpace = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = headerSpace, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    Box(Modifier.animateItemPlacement(FaraiMotion.spatial())) {
                        when (row) {
                            is TranscriptRow.Activity -> ActivityGroup(row.tools, row.key)
                            is TranscriptRow.Web -> WebTrace(row.tools, row.key)
                            is TranscriptRow.Item -> when (val item = row.item) {
                                is ChatItem.User -> UserMessage(item.text, editable = item.key == lastUserKey) { t ->
                                    editing = true
                                    input = TextFieldValue(t, TextRange(t.length))
                                }
                                is ChatItem.Assistant -> AssistantMessage(item.text, settings.ui.markdown)
                                is ChatItem.Tool -> when (item.name) {
                                    "task" -> SubagentCard(item) { onNavigate(NavTarget.OpenFile(it)) }
                                    "finish" -> ActivityGroup(listOf(item), item.key)
                                    else -> FileChangeCard(item) { onNavigate(NavTarget.OpenFile(it)) }
                                }
                                is ChatItem.Notice -> NoticeCard(item.text, item.isError, settings.ui.markdown)
                                is ChatItem.Finished -> FinishedCard(item.summary, settings.ui.markdown)
                                is ChatItem.Files -> dev.harness.ui.files.ProducedFilesCard(item.files) { onNavigate(NavTarget.OpenFile(it)) }
                                is ChatItem.Compacted -> CompactedCard(item.summary, item.removed, settings.ui.markdown, item.key in expandedCompactions) {
                                    expandedCompactions = if (item.key in expandedCompactions) expandedCompactions - item.key else expandedCompactions + item.key
                                }
                            }
                        }
                    }
                }
                if (ui.streaming.isNotEmpty()) item(key = "streaming") { StreamingMessage(ui.streaming, settings.ui.markdown) }
                if (showWorking) item(key = "working") {
                    Box(Modifier.animateItemPlacement()) { WorkingRow(workingLabel(ui.items, false), runStart) }
                }
            }
            val empty = !ui.loading && ui.items.isEmpty() && ui.streaming.isEmpty()
            androidx.compose.animation.AnimatedVisibility(empty, enter = fadeIn(tween(500)), exit = fadeOut(tween(250)), modifier = Modifier.fillMaxSize()) {
                Welcome(
                    starters = starters,
                    providerConfigured = ui.providerConfigured,
                    onSetup = { onNavigate(NavTarget.Provider) },
                    onPick = { s -> input = TextFieldValue(s, TextRange(s.length)) },
                )
            }
            // Floating header: a soft sky fade so text slides under the glass pill.
            Box(
                Modifier.fillMaxWidth().height(headerSpace + 8.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(c.background.copy(alpha = if (c.isDark) 0.55f else 0.35f), Color.Transparent))),
            )
            Box(Modifier.align(Alignment.TopCenter)) {
            NavBar(
                title = ui.title,
                subtitle = listOfNotNull(agentDef?.label ?: ui.agent.replaceFirstChar { it.uppercase() }, ui.model.takeIf { it.isNotBlank() }).joinToString(" · "),
                collapsed = true,
                backLabel = "Sessions",
                onBack = { vm.saveNow(); onBack() },
                actions = {
                    if (todos.isNotEmpty()) {
                        val open = todos.count { it.status != TodoItem.DONE && it.status != TodoItem.CANCELLED }
                        CircleButton(Icons.Rounded.Checklist, tr("Tasks"), badge = open) { todoSheet = true }
                    }
                    Box {
                        CircleButton(Icons.Rounded.MoreHoriz, tr("More")) { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(c.surface)) {
                            MenuItem(tr("Share transcript"), Icons.Rounded.IosShare) { menu = false; vm.exportTranscript() }
                            MenuItem("Commands", Icons.Rounded.Terminal) { menu = false; vm.send("/help") }
                            MenuItem("System prompt", Icons.Rounded.Code) { menu = false; vm.send("/context") }
                            MenuItem(tr("Save now"), Icons.Rounded.Save) { menu = false; vm.saveNow() }
                            Box(Modifier.fillMaxWidth().height(6.dp))
                            MenuItem("Prompts", Icons.Rounded.EditNote) { menu = false; onNavigate(NavTarget.Prompts) }
                            MenuItem(tr("Settings"), Icons.Rounded.Settings) { menu = false; onNavigate(NavTarget.Settings) }
                        }
                    }
                },
            )
            }
            // Jump back down.
            androidx.compose.animation.AnimatedVisibility(
                !atBottom && totalRows > 0,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut() + scaleOut(targetScale = 0.7f),
            ) {
                CircleButton(Icons.Rounded.ArrowDownward, tr("Scroll to bottom"), container = c.surface, modifier = Modifier.floating(CircleShape, c.isDark)) {
                    follow = true
                    scope.launch { listState.animateScrollToItem((totalRows - 1).coerceAtLeast(0), Int.MAX_VALUE / 2) }
                }
            }
        }

        // ---- Composer ----
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            val goal = ui.goal
            val open = todos.count { it.status != TodoItem.DONE && it.status != TodoItem.CANCELLED }
            AnimatedVisibility(ui.running || goal != null || todos.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.padding(start = 4.dp, bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.running) LivePill("Working", c.ember) { follow = true; scope.launch { if (totalRows > 0) listState.animateScrollToItem(totalRows - 1, Int.MAX_VALUE / 2) } }
                    if (goal != null) {
                        val tint = when (goal.status) { dev.harness.core.Goal.ACTIVE -> c.accent; dev.harness.core.Goal.COMPLETE -> c.green; else -> c.orange }
                        StatusChip(Icons.Rounded.Flag, "Goal · ${goal.status.lowercase()} · ${goal.rounds}", tint) { vm.send("/goal status") }
                    }
                    if (todos.isNotEmpty()) StatusChip(Icons.Rounded.Checklist, trf("{0}/{1} tasks", todos.size - open, todos.size), c.green) { todoSheet = true }
                }
            }
            val suggestions = remember(input.text) { vm.suggestions(input.text).take(7) }
            AnimatedVisibility(
                visible = suggestions.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut() + slideOutVertically { it / 4 },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).floating(RoundedCornerShape(22.dp), c.isDark)
                        .clip(RoundedCornerShape(22.dp)).background(c.surface).border(1.dp, c.codeBorder, RoundedCornerShape(22.dp)).padding(vertical = 6.dp),
                ) {
                    suggestions.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                input = TextFieldValue(s.insert, TextRange(s.insert.length))
                            }.padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.label, style = Harness.type.mono.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                            if (s.usage.isNotEmpty()) Text(" " + s.usage, style = Harness.type.monoSmall, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(12.dp))
                            Text(s.description, style = Harness.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            AnimatedVisibility(editing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.padding(start = 6.dp, end = 4.dp, bottom = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Edit, null, tint = c.accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Editing your last message"), style = Harness.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.weight(1f))
                    CircleButton(Icons.Rounded.Close, tr("Cancel edit"), size = 28.dp, container = c.surfaceElevated, tint = c.secondaryLabel) {
                        editing = false; input = TextFieldValue("")
                    }
                }
            }
            Composer(
                value = input,
                editing = editing,
                onValueChange = { input = it },
                running = ui.running,
                placeholder = if (ui.running) tr("Working… type to queue a note") else tr("Message FARAI"),
                agentLabel = agentDef?.label ?: ui.agent.replaceFirstChar { it.uppercase() },
                model = ui.model.ifBlank { settings.provider.model },
                effort = run {
                    val RE = dev.harness.net.ReasoningEffort
                    val lv = RE.levelsFor(settings.provider.copy(model = ui.model.ifBlank { settings.provider.model }))
                    val e = RE.normalize(settings.provider.reasoningEffort)
                    when {
                        lv != null && lv.isEmpty() -> null
                        e == null -> "Auto"
                        else -> RE.label(lv?.let { RE.nearest(e, it) } ?: e)
                    }
                },
                onPlus = { if (!input.text.startsWith("/")) input = TextFieldValue("/", TextRange(1)) },
                onChips = { runSheet = true },
                onSend = {
                    if (editing && input.text.isNotBlank()) { follow = true; vm.editLast(input.text); editing = false; input = TextFieldValue("") }
                    else if (ui.running && input.text.isBlank()) vm.stop()
                    else if (!ui.running && input.text.isNotBlank()) { follow = true; vm.send(input.text); input = TextFieldValue("") }
                },
            )
        }
    }

    if (runSheet) RunSheet(
        agents = ui.agents.map { Triple(it.name, it.label, it.description) },
        agent = ui.agent,
        effort = settings.provider.reasoningEffort,
        effortLevels = dev.harness.net.ReasoningEffort.levelsFor(settings.provider.copy(model = ui.model.ifBlank { settings.provider.model })),
        model = ui.model.ifBlank { settings.provider.model },
        onAgent = { vm.setAgent(it) },
        onEffort = { vm.send("/reasoning ${it.ifEmpty { "default" }}") },
        onModel = { runSheet = false; onNavigate(NavTarget.Provider) },
        onDismiss = { runSheet = false },
    )

    if (todoSheet) {
        ModalBottomSheet(onDismissRequest = { todoSheet = false }, containerColor = c.background, dragHandle = { SheetHandle() }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                val done = todos.count { it.status == TodoItem.DONE }
                Text(tr("Tasks"), style = Harness.type.title2, color = c.label)
                Text(trf("{0} of {1} done", done, todos.size), style = Harness.type.subhead, color = c.secondaryLabel)
                Spacer(Modifier.height(14.dp))
                val progress by animateFloatAsState(if (todos.isEmpty()) 0f else done.toFloat() / todos.size, FaraiMotion.slow(), label = "todo")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surfaceElevated)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(CircleShape).background(c.green))
                }
                Spacer(Modifier.height(14.dp))
                Column(Modifier.clip(RoundedCornerShape(22.dp)).background(c.surface)) {
                    todos.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (t.status) {
                                TodoItem.DONE -> Box(Modifier.size(20.dp).clip(CircleShape).background(c.green), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                                TodoItem.IN_PROGRESS -> Spark(20.dp, live = true)
                                TodoItem.CANCELLED -> Icon(Icons.Rounded.Close, null, tint = c.tertiaryLabel, modifier = Modifier.size(20.dp))
                                else -> Box(Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, c.tertiaryLabel, CircleShape))
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                t.content, style = Harness.type.body,
                                color = if (t.status == TodoItem.DONE || t.status == TodoItem.CANCELLED) c.secondaryLabel else c.label,
                                textDecoration = if (t.status == TodoItem.CANCELLED) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            )
                        }
                        if (i < todos.lastIndex) dev.harness.ui.components.RowDivider()
                    }
                }
            }
        }
    }

    ui.confirmation?.let { conf -> PermissionDialog(conf) { vm.answerConfirmation(it) } }
    ui.question?.let { q -> QuestionSheet(q.questions) { vm.answerQuestion(it) } }
}

@Composable
fun SheetHandle() {
    Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Harness.colors.tertiaryLabel.copy(alpha = 0.5f)))
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = Harness.colors
    DropdownMenuItem(
        text = { Text(text, style = Harness.type.subhead.copy(fontWeight = FontWeight.Medium), color = c.label) },
        leadingIcon = { Icon(icon, null, tint = c.secondaryLabel, modifier = Modifier.size(19.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = tint)
    }
}

/** Empty chat: the aurora, the spark, and a few ways to start. */
@Composable
private fun Welcome(starters: List<String>, providerConfigured: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    val c = Harness.colors
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val rise by animateDpAsState(if (shown) 0.dp else 18.dp, FaraiMotion.slow(), label = "rise")
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "fade")
    // Just the mark and one line, dead centre: no starter chips (Farrel, 2026-11-06).
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).offset(y = rise).padding(horizontal = 28.dp).alpha(fade),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            dev.harness.ui.components.FaraiLogo(68.dp)
            Spacer(Modifier.height(22.dp))
            // A new line each time a session opens, matched to the hour and the app language.
            val lang = dev.harness.ui.i18n.I18n.lang
            val greeting = rememberSaveable(lang) { Greetings.pick(lang) }
            Text(greeting, style = Harness.type.title1, color = c.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(tr("Describe a task, or type / for every command."), style = Harness.type.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center)
            if (!providerConfigured) {
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.clip(CircleShape).background(c.accent).clickable(onClick = onSetup).padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Key, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Connect a model provider"), style = Harness.type.label.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                }
            }
        }
    }
}


/** The floating composer: text on top, context chips and the morphing send/stop button below. */
@Composable
private fun Composer(
    value: TextFieldValue,
    editing: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    running: Boolean,
    placeholder: String,
    agentLabel: String,
    model: String,
    effort: String?,
    onPlus: () -> Unit,
    onChips: () -> Unit,
    onSend: () -> Unit,
) {
    val c = Harness.colors
    val shape = RoundedCornerShape(28.dp)
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) c.accent.copy(alpha = 0.45f) else c.codeBorder, label = "composerBorder")
    // Small pill while the keyboard is down; with the keyboard up it grows into a box with the
    // agent / model / effort chips underneath. The text field keeps its slot, so focus survives.
    @OptIn(ExperimentalLayoutApi::class)
    val expanded = WindowInsets.isImeVisible
    val corner by animateDpAsState(if (expanded) 26.dp else 28.dp, FaraiMotion.spatial(), label = "composerCorner")
    val boxShape = RoundedCornerShape(corner)
    Column(
        Modifier.fillMaxWidth().floating(boxShape, c.isDark).clip(boxShape).background(c.glass).border(1.dp, border, boxShape)
            .animateContentSize(FaraiMotion.spatial()).padding(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (!expanded) CircleButton(Icons.Rounded.Add, "Commands", size = 42.dp, container = Color.Transparent, tint = c.secondaryLabel, onClick = onPlus)
            Box(
                Modifier.weight(1f).heightIn(min = if (expanded) 52.dp else 42.dp)
                    .padding(start = if (expanded) 14.dp else 4.dp, end = 6.dp),
                contentAlignment = if (expanded) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text(placeholder, style = Harness.type.body, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 10.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = Harness.type.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = if (expanded) 8 else 3,
                    keyboardOptions = KeyboardOptions(autoCorrect = !value.text.startsWith("/")),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).onFocus { focused = it },
                )
            }
            if (!expanded) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(42.dp).pressScale(interaction, 0.88f).clip(CircleShape).clickable(interaction, null, onClick = onChips),
                    contentAlignment = Alignment.Center,
                ) { Spark(20.dp, live = running) }
                Spacer(Modifier.width(4.dp))
                SendButton(running = running && !editing, hasText = value.text.isNotBlank(), onClick = onSend)
            }
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(220)) + expandVertically(FaraiMotion.spatial()), exit = fadeOut(tween(120)) + shrinkVertically(FaraiMotion.spatial())) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Icons.Rounded.Add, "Commands", size = 38.dp, container = Color.Transparent, tint = c.secondaryLabel, onClick = onPlus)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ContextChip(agentLabel, { Spark(14.dp, live = running) }, onChips)
                    ContextChip(model.substringAfterLast('/').ifBlank { "Model" }, { Icon(Icons.Rounded.Memory, null, tint = c.secondaryLabel, modifier = Modifier.size(15.dp)) }, onChips)
                    if (effort != null) ContextChip(effort, { Icon(Icons.Rounded.Tune, null, tint = c.secondaryLabel, modifier = Modifier.size(15.dp)) }, onChips)
                }
                Spacer(Modifier.width(6.dp))
                SendButton(running = running && !editing, hasText = value.text.isNotBlank(), onClick = onSend)
            }
        }
    }
}

private fun Modifier.onFocus(onChange: (Boolean) -> Unit): Modifier =
    this.then(Modifier.onFocusChanged { onChange(it.isFocused) })

@Composable
private fun ContextChip(label: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val c = Harness.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.heightIn(min = 34.dp).pressScale(interaction).clip(CircleShape).background(c.surfaceElevated)
            .clickable(interaction, null, onClick = onClick).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
        Text(label, style = Harness.type.label, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
    }
}

/** Send and stop are one control: the circle morphs into a rounded square and turns red. */
@Composable
private fun SendButton(running: Boolean, hasText: Boolean, onClick: () -> Unit) {
    val c = Harness.colors
    val haptics = LocalHapticFeedback.current
    val stop = running && !hasText
    val enabled = stop || (hasText && !running)
    val bg by animateColorAsState(
        when { stop -> c.red.copy(alpha = 0.14f); enabled -> c.accent; else -> c.surfaceElevated },
        FaraiMotion.effects(), label = "sendBg",
    )
    val corner by animateDpAsState(if (stop) 13.dp else 20.dp, FaraiMotion.bouncy(), label = "sendShape")
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(if (enabled) 1f else 0.92f, FaraiMotion.bouncy(), label = "sendScale")
    Box(
        Modifier.size(40.dp).scale(scale).pressScale(interaction, 0.86f).clip(RoundedCornerShape(corner)).background(bg)
            .clickable(interaction, null, enabled = enabled) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(stop, transitionSpec = { (scaleIn(initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) }, label = "sendIcon") { s ->
            if (s) Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(c.red))
            else Icon(Icons.Rounded.ArrowUpward, tr("Send"), tint = if (enabled) Color.White else c.tertiaryLabel, modifier = Modifier.size(21.dp))
        }
    }
}

/** Agent, reasoning effort and model in one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunSheet(
    agents: List<Triple<String, String, String>>,
    agent: String,
    effort: String,
    effortLevels: List<String>?,
    model: String,
    onAgent: (String) -> Unit,
    onEffort: (String) -> Unit,
    onModel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Harness.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.background, dragHandle = { SheetHandle() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 30.dp).verticalScroll(rememberScrollState())) {
            Text("Run settings", style = Harness.type.title2, color = c.label, modifier = Modifier.padding(start = 6.dp))
            Text(tr("Who works, how hard it thinks, and on which model."), style = Harness.type.subhead, color = c.secondaryLabel, modifier = Modifier.padding(start = 6.dp, top = 2.dp))

            SheetLabel("Agent")
            Column(Modifier.clip(RoundedCornerShape(22.dp)).background(c.surface)) {
                agents.forEachIndexed { i, (name, label, desc) ->
                    val on = name.equals(agent, true)
                    val bg by animateColorAsState(if (on) c.accentSoft else Color.Transparent, label = "agentRow")
                    Row(
                        Modifier.fillMaxWidth().background(bg).clickable { onAgent(name) }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = Harness.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                            if (desc.isNotBlank()) Text(desc, style = Harness.type.footnote, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        AnimatedVisibility(on, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                            Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (i < agents.lastIndex) dev.harness.ui.components.RowDivider()
                }
            }

            SheetLabel("Reasoning")
            val RE = dev.harness.net.ReasoningEffort
            val modelLevels = effortLevels
            if (modelLevels != null && modelLevels.isEmpty()) {
                Text(tr("This model has no reasoning setting."), style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            } else {
                val shown = modelLevels ?: listOf("low", "medium", "high")
                val levels = listOf("" to "Auto") + shown.map { it to RE.label(it) }
                val sent = RE.normalize(effort)?.let { e -> modelLevels?.let { RE.nearest(e, it) } ?: e }
                val selected = levels.indexOfFirst { it.first == sent }.let { if (it < 0) 0 else it }
                if (levels.size <= 5) {
                    dev.harness.ui.components.SegmentedControl(
                        options = levels.map { it.second },
                        selected = selected,
                        onSelect = { onEffort(levels[it].first) },
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        levels.forEachIndexed { i, (value, label) ->
                            val on = i == selected
                            val bg by animateColorAsState(if (on) c.accent else c.surfaceElevated, label = "effortPill")
                            val fg by animateColorAsState(if (on) Color.White else c.label, label = "effortPillText")
                            Box(
                                Modifier.height(40.dp).clip(CircleShape).background(bg).clickable { onEffort(value) }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = Harness.type.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1) }
                        }
                    }
                }
                val note = when {
                    modelLevels == null && effort.isNotEmpty() && levels.none { it.first == effort } -> trf("Currently “{0}” (set with /reasoning).", effort)
                    modelLevels != null -> trf("Up to {0} on this model.", RE.label(modelLevels.last()))
                    else -> null
                }
                if (note != null) Text(note, style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            }

            SheetLabel("Model")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).clickable(onClick = onModel).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Memory, null, tint = c.label, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.ifBlank { tr("Not set") }, style = Harness.type.body.copy(fontWeight = FontWeight.SemiBold, fontFamily = dev.harness.ui.theme.GeistMono), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("Change provider and model"), style = Harness.type.footnote, color = c.secondaryLabel)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.tertiaryLabel)
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = Harness.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = Harness.colors.accent, modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp))
}


/** Ideas shown on an empty session: a big pool, four at random each time. "…" = keep typing. */
object Starters {
    val pool = listOf(
        "Explain this project", "Find and fix a bug in …", "Write tests for …", "/init",
        "Refactor … to be easier to read", "Add type hints to …", "Write a README for this project",
        "Review my last changes", "Make a Python script that renames photos by date",
        "Build a to-do CLI in Python", "Create a FastAPI hello-world server", "Set up a Node.js project with TypeScript",
        "Scrape the titles from a web page", "Convert this CSV to JSON: …", "Explain what this error means: …",
        "Write a bash script that backs up my workspace", "Summarise the files in /sdcard/Download",
        "Speed up …", "Add logging to …", "Write docstrings for …", "Make a simple HTML landing page",
        "Create a Flask app with a login form", "Write a regex that matches …", "Plan the steps to build …",
        "Find TODO comments in this project", "Turn this function into a class: …", "Create a .gitignore for Python",
        "Initialise a git repo and make the first commit", "Write unit tests with pytest for …",
        "Explain this code line by line: …", "Make a CLI that converts Markdown to HTML", "Check this project for security issues",
        "Install pandas and analyse data.csv", "Write a SQL schema for a blog", "Draw an ASCII chart of …",
        "Make a Telegram bot skeleton in Python", "Port this script from Python to JavaScript: …",
        "Generate sample JSON data for testing", "Find the largest files in the workspace", "Write a commit message for my changes",
    )

    fun pick(n: Int): ArrayList<String> = ArrayList(pool.shuffled().take(n))

    fun icon(s: String): androidx.compose.ui.graphics.vector.ImageVector {
        val l = s.lowercase()
        return when {
            l.startsWith("/") -> Icons.Rounded.AutoAwesome
            "bug" in l || "error" in l || "security" in l -> Icons.Rounded.BugReport
            "test" in l -> Icons.Rounded.Science
            "explain" in l || "review" in l || "summar" in l || "find" in l -> Icons.Rounded.TravelExplore
            "git" in l || "commit" in l -> Icons.Rounded.Commit
            "html" in l || "page" in l || "web" in l -> Icons.Rounded.Language
            "script" in l || "cli" in l || "bash" in l -> Icons.Rounded.Terminal
            "data" in l || "csv" in l || "json" in l || "sql" in l -> Icons.Rounded.DataObject
            else -> Icons.Rounded.Lightbulb
        }
    }
}
