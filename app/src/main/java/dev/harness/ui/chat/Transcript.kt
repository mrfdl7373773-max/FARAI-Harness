package dev.harness.ui.chat

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.combinedClickable
import dev.harness.ui.components.easyLongPress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.harness.net.HarnessJson
import dev.harness.ui.components.CodeBlock
import dev.harness.ui.components.MarkdownBlock
import dev.harness.ui.components.PulseDot
import dev.harness.ui.components.ShimmerText
import dev.harness.ui.components.Spark
import dev.harness.ui.components.VeilText
import dev.harness.ui.components.argSummary
import dev.harness.ui.components.clickableNoRipple
import dev.harness.ui.theme.Harness
import dev.harness.ui.theme.HarnessColors
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// The transcript: the agent's words sit on the page with no bubble, the user's sit in
// a soft pill, and the work in between collapses into quiet activity groups, while file changes
// stay visible as diff cards. Everything alive carries the FARAI spark.

private val FileTools = setOf("write", "edit", "multiedit", "patch")

/** What the list draws: single items, or a run of consecutive non-file tool calls. */
sealed interface TranscriptRow {
    val key: String
    data class Item(val item: ChatItem) : TranscriptRow { override val key get() = item.key }
    data class Activity(override val key: String, val tools: List<ChatItem.Tool>) : TranscriptRow
    /** A run of web searches and page reads, drawn as one research trace. */
    data class Web(override val key: String, val tools: List<ChatItem.Tool>) : TranscriptRow
}

fun transcriptRows(items: List<ChatItem>): List<TranscriptRow> {
    val out = ArrayList<TranscriptRow>(items.size)
    var run = ArrayList<ChatItem.Tool>()
    var web = ArrayList<ChatItem.Tool>()
    fun flush() { if (run.isNotEmpty()) { out += TranscriptRow.Activity("act-" + run.first().key, run); run = ArrayList() } }
    fun flushWeb() { if (web.isNotEmpty()) { out += TranscriptRow.Web("web-" + web.first().key, web); web = ArrayList() } }
    for (i in items) {
        when {
            i is ChatItem.Tool && i.name in WebTools -> { flush(); web += i }
            i is ChatItem.Tool && i.name !in FileTools && i.name != "task" && i.name != "finish" -> { flushWeb(); run += i }
            else -> { flush(); flushWeb(); out += TranscriptRow.Item(i) }
        }
    }
    flush(); flushWeb()
    return out
}

private fun json(raw: String): JsonObject? = runCatching { HarnessJson.parseToJsonElement(raw).jsonObject }.getOrNull()
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

// ---- Messages ---------------------------------------------------------------------------------

/**
 * The user's message: a soft pill. Long-press opens Copy / Select all; a tap on the newest one
 * reveals a small pencil to edit and resend it (older messages are history and stay put).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UserMessage(text: String, editable: Boolean = false, onEdit: (String) -> Unit = {}) {
    val c = Harness.colors
    var menu by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var selectAll by remember { mutableStateOf(false) }
    var showPencil by remember(editable) { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 6.dp), horizontalAlignment = Alignment.End) {
        Box {
            Text(
                text,
                style = Harness.type.body,
                color = c.userBubbleText,
                modifier = Modifier.clip(RoundedCornerShape(26.dp)).background(c.userBubble).border(1.dp, c.glassBorder, RoundedCornerShape(26.dp))
                    .easyLongPress { at -> press = at; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }
                    .clickable { if (editable) showPencil = !showPencil }
                    .padding(horizontal = 18.dp, vertical = 13.dp),
            )
            MessageMenu(menu, text, onDismiss = { menu = false }, onSelectAll = { menu = false; selectAll = true }, press = press)
        }
        AnimatedVisibility(editable && showPencil, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier.padding(top = 6.dp).clip(CircleShape).background(c.surfaceElevated)
                    .clickable { showPencil = false; onEdit(text) }.padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Edit, tr("Edit message"), tint = c.secondaryLabel, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Edit", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
            }
        }
    }
    if (selectAll) SelectAllSheet(text) { selectAll = false }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AssistantMessage(text: String, markdown: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var selectAll by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth().answerCard()
            .easyLongPress { at -> press = at; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true },
    ) {
        Column(Modifier.fillMaxWidth()) {
            MarkdownBlock(text, Harness.colors.label, Modifier.fillMaxWidth(), enabled = markdown, selectable = false)
        }
        MessageMenu(menu, text, onDismiss = { menu = false }, onSelectAll = { menu = false; selectAll = true }, press = press)
    }
    if (selectAll) SelectAllSheet(text) { selectAll = false }
}

/** One-tap actions under every finished answer: Copy (with a tick), Select text, Share. */
@Composable
fun MessageActions(text: String, onSelect: () -> Unit) {
    val c = Harness.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionChip(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, if (copied) tr("Copied") else tr("Copy"), if (copied) c.green else c.secondaryLabel) {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            copied = true
        }
        Spacer(Modifier.width(6.dp))
        ActionChip(Icons.Rounded.SelectAll, tr("Select"), c.secondaryLabel, onSelect)
        Spacer(Modifier.width(6.dp))
        ActionChip(Icons.Rounded.Share, tr("Share"), c.secondaryLabel) { dev.harness.ui.shareText(context, "Message", text) }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = Harness.type.caption.copy(fontWeight = FontWeight.Medium), color = tint)
    }
}

/** Long-press menu on a message: icon + label rows. */
@Composable
fun MessageMenu(expanded: Boolean, text: String, onDismiss: () -> Unit, onSelectAll: () -> Unit, press: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    dev.harness.ui.components.FingerMenu(expanded, press, onDismiss) {
        MenuRow(Icons.Rounded.ContentCopy, tr("Copy")) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)); onDismiss() }
        MenuRow(Icons.Rounded.SelectAll, tr("Select"), onClick = onSelectAll)
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, tint: Color = Harness.colors.label, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, style = Harness.type.subhead.copy(fontWeight = FontWeight.Medium), color = tint) },
        leadingIcon = { Icon(icon, null, tint = if (tint == Harness.colors.label) Harness.colors.secondaryLabel else tint, modifier = Modifier.size(19.dp)) },
        onClick = onClick,
    )
}

/**
 * "Select all" for one message: the whole message, and only it, opens selected, with Copy and
 * Share; drag the handles to narrow it down.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SelectAllSheet(text: String, onDismiss: () -> Unit) {
    val c = Harness.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(0, text.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.background, dragHandle = { SheetHandle() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sel = value.selection
                Text(
                    if (sel.collapsed) tr("Nothing selected") else trf("{0} characters selected", sel.length),
                    style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                SheetAction(Icons.Rounded.SelectAll, tr("All")) { value = value.copy(selection = androidx.compose.ui.text.TextRange(0, text.length)) }
                Spacer(Modifier.width(6.dp))
                SheetAction(Icons.Rounded.ContentCopy, tr("Copy")) {
                    val sel2 = value.selection
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(if (sel2.collapsed) text else text.substring(sel2.min, sel2.max)))
                    onDismiss()
                }
                Spacer(Modifier.width(6.dp))
                SheetAction(Icons.Rounded.Share, tr("Share")) {
                    val sel2 = value.selection
                    dev.harness.ui.shareText(context, "Message", if (sel2.collapsed) text else text.substring(sel2.min, sel2.max))
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(18.dp)).background(c.surface)
                    .border(1.dp, c.codeBorder, RoundedCornerShape(18.dp)).padding(16.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = text) },
                    readOnly = true,
                    textStyle = Harness.type.body.copy(color = c.label),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).androidx_verticalScroll(),
                )
            }
        }
    }
}

@Composable
private fun Modifier.androidx_verticalScroll(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Harness.colors
    Row(
        Modifier.clip(CircleShape).background(c.surfaceElevated).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.label, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Harness.type.label, color = c.label)
    }
}

/**
 * Streaming reply: settled paragraphs render as markdown, the paragraph still being written
 * streams under the veil, so nothing jumps when the turn ends.
 */
@Composable
fun StreamingMessage(text: String, markdown: Boolean) {
    val c = Harness.colors
    val split = remember(text) { settledSplit(text) }
    Column(Modifier.fillMaxWidth().answerCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (split > 0) MarkdownBlock(text.substring(0, split).trimEnd(), c.label, enabled = markdown)
        val tail = text.substring(split).trimStart('\n')
        if (tail.isNotEmpty()) {
            if (tail.trimStart().startsWith("```")) {
                // An open code fence streams as code, line by line.
                val body = tail.trimStart().removePrefix("```").substringAfter('\n', "")
                val lang = tail.trimStart().removePrefix("```").substringBefore('\n').trim()
                CodeBlock(body, lang)
            } else VeilText(tail.replace(Regex("\\*\\*|__|`"), ""), Harness.type.body, c.label)
        }
    }
}

/** Index after the last blank line that is not inside a code fence. */
private fun settledSplit(text: String): Int {
    var inFence = false
    var last = 0
    var i = 0
    val lines = text.split('\n')
    for ((n, line) in lines.withIndex()) {
        if (line.trimStart().startsWith("```")) inFence = !inFence
        i += line.length + 1
        if (!inFence && line.isBlank() && n < lines.lastIndex) last = i.coerceAtMost(text.length)
    }
    return last
}

/** Tail of a running turn: what the agent is doing right now. */
@Composable
fun WorkingRow(label: String, sinceMs: Long?) {
    dev.harness.ui.components.WorkingIndicator(label, sinceMs, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

fun workingLabel(items: List<ChatItem>, streaming: Boolean): String {
    if (streaming) return tr("Writing…")
    val live = items.lastOrNull { it is ChatItem.Tool && it.running } as? ChatItem.Tool ?: return tr("Thinking…")
    val deepest = generateSequence(live) { t -> t.children.lastOrNull { it.running } }.last()
    val o = json(deepest.arguments)
    val file = o?.s("path")?.substringAfterLast('/')
    return when (deepest.name) {
        "bash" -> "Running " + (o?.s("description")?.takeIf { it.isNotBlank() } ?: o?.s("command")?.take(28) ?: "command") + "…"
        "write" -> "Writing ${file ?: "file"}…"
        "edit", "multiedit", "patch" -> "Editing ${file ?: "files"}…"
        "read" -> "Reading ${file ?: "file"}…"
        "grep", "glob" -> "Searching…"
        "web_fetch" -> (o?.get("urls") as? kotlinx.serialization.json.JsonArray)?.size?.takeIf { it > 1 }?.let { trf("Reading {0} pages", it) + "…" }
            ?: trf("Reading {0}…", (o?.s("url") ?: (o?.get("urls") as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
                ?.substringAfter("://")?.substringBefore('/')?.removePrefix("www.") ?: "page")
        "web_search" -> tr("Searching the web…")
        "task" -> "Delegating to ${o?.s("agent") ?: "subagent"}…"
        "question" -> "Waiting for you…"
        else -> trf("Using {0}…", deepest.name)
    }
}

// ---- Activity groups --------------------------------------------------------------------------

private data class Verb(val icon: ImageVector, val past: String, val present: String, val noun: String, val plural: String)

private fun verb(name: String): Verb = when (name) {
    "bash" -> Verb(Icons.Rounded.Terminal, "Ran", "Running", "command", "commands")
    "job" -> Verb(Icons.Rounded.Terminal, "Checked", "Checking", "job", "jobs")
    "read" -> Verb(Icons.Rounded.Description, "Read", "Reading", "file", "files")
    "glob" -> Verb(Icons.Rounded.FolderOpen, "Listed", "Listing", "pattern", "patterns")
    "grep" -> Verb(Icons.Rounded.Search, "Searched", "Searching", "pattern", "patterns")
    "web_fetch" -> Verb(Icons.Rounded.Language, "Fetched", "Fetching", "page", "pages")
    "web_search" -> Verb(Icons.Rounded.TravelExplore, "Searched", "Searching", "query", "queries")
    "todo" -> Verb(Icons.Rounded.Checklist, "Updated", "Updating", "task list", "task lists")
    "question" -> Verb(Icons.Rounded.HelpOutline, "Asked", "Asking", "question", "questions")
    "skill" -> Verb(Icons.Rounded.AutoAwesome, "Loaded", "Loading", "skill", "skills")
    "mcp_resource" -> Verb(Icons.Rounded.Hub, "Read", "Reading", "resource", "resources")
    else -> if (name.contains('_')) Verb(Icons.Rounded.Hub, "Called", "Calling", name.substringAfter('_'), name.substringAfter('_'))
    else Verb(Icons.Rounded.Build, tr("Used"), tr("Using"), name, name)
}

private fun groupSummary(tools: List<ChatItem.Tool>): String {
    val byName = LinkedHashMap<String, Int>()
    tools.forEach { byName[verb(it.name).past + "\u0000" + verb(it.name).noun] = (byName[verb(it.name).past + "\u0000" + verb(it.name).noun] ?: 0) + 1 }
    return byName.entries.joinToString(" · ") { (k, n) ->
        val v = tools.first { verb(it.name).past + "\u0000" + verb(it.name).noun == k }.let { verb(it.name) }
        "${tr(v.past)} $n ${if (n == 1) v.noun else v.plural}"
    }.replaceFirstChar { it.uppercase() }
}

@Composable
fun ActivityGroup(tools: List<ChatItem.Tool>, key: String) {
    val c = Harness.colors
    val running = tools.any { it.running }
    val failed = tools.count { it.isError }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (expanded) 90f else 0f, dev.harness.ui.components.FaraiMotion.spatial(), label = "chev")
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).animateContentSize(dev.harness.ui.components.FaraiMotion.spatial())) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.tertiaryLabel, modifier = Modifier.size(18.dp).rotate(rot))
            Spacer(Modifier.width(6.dp))
            val summary = groupSummary(tools)
            if (running) ShimmerText(summary, Harness.type.footnote, c.secondaryLabel, c.label)
            else Text(summary, style = Harness.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (failed > 0) {
                Spacer(Modifier.width(8.dp))
                Text("$failed failed", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.red)
            }
        }
        // The running call stays in view even when the group is folded.
        val live = tools.lastOrNull { it.running }
        AnimatedVisibility(!expanded && live != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            if (live != null) Column(Modifier.padding(start = 30.dp, end = 6.dp)) { ToolLine(live, last = true, rail = false) }
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp)) {
                tools.forEachIndexed { i, t -> ToolLine(t, last = i == tools.lastIndex, rail = true) }
            }
        }
    }
}

/** One call inside a group, on the rail: icon, verb and target, status; tap for details. */
@Composable
private fun ToolLine(t: ChatItem.Tool, last: Boolean, rail: Boolean) {
    val c = Harness.colors
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    val v = verb(t.name)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (rail) Rail(last, c)
        Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(v.icon, null, tint = if (t.isError) c.red else c.secondaryLabel, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                val target = argSummary(t.name, t.arguments)
                val line = buildAnnotatedString {
                    withStyle(SpanStyle(color = c.secondaryLabel)) { append(tr(if (t.running) v.present else v.past)) }
                    append("  ")
                    withStyle(SpanStyle(color = c.label, fontFamily = dev.harness.ui.theme.GeistMono, fontSize = Harness.type.monoSmall.fontSize)) { append(target) }
                }
                Text(line, style = Harness.type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatusMark(t.running, t.isError)
            }
            if (t.running && t.liveText.isNotBlank()) LiveTail(t.liveText)
            AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(start = 6.dp, end = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { ToolDetail(t) }
            }
        }
    }
}

@Composable
private fun Rail(last: Boolean, c: HarnessColors) {
    Canvas(Modifier.width(16.dp).fillMaxHeight()) {
        val x = 4.dp.toPx()
        val mid = 21.dp.toPx().coerceAtMost(size.height)
        val bend = 6.dp.toPx()
        val p = Path()
        p.moveTo(x, 0f); p.lineTo(x, mid - bend)
        p.quadraticBezierTo(x, mid, x + bend, mid)
        p.lineTo(size.width, mid)
        if (!last) { p.moveTo(x, mid - bend); p.lineTo(x, size.height) }
        drawPath(p, c.rail, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
fun StatusMark(running: Boolean, error: Boolean) {
    val c = Harness.colors
    AnimatedContent(
        when { running -> 0; error -> 2; else -> 1 },
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() },
        label = "status",
    ) { s ->
        when (s) {
            0 -> Spark(14.dp, live = true)
            2 -> Icon(Icons.Rounded.Close, tr("Failed"), tint = c.red, modifier = Modifier.size(15.dp))
            else -> Icon(Icons.Rounded.Check, "Done", tint = c.green, modifier = Modifier.size(15.dp))
        }
    }
}

/** Live output of a running command: the last lines, fading upwards. */
@Composable
private fun LiveTail(text: String) {
    val c = Harness.colors
    val lines = remember(text) { text.trimEnd().lines().takeLast(4) }
    Column(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(c.codeBackground).border(1.dp, c.codeBorder, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        lines.forEachIndexed { i, l ->
            Text(
                l, style = Harness.type.monoSmall, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.35f + 0.65f * ((i + 1f) / lines.size)),
            )
        }
    }
}

@Composable
private fun ToolDetail(t: ChatItem.Tool) {
    val o = json(t.arguments)
    when (t.name) {
        "bash" -> {
            val cmd = o?.s("command").orEmpty()
            TerminalBlock(cmd, t.result)
        }
        else -> {
            CodeBlock(prettyJson(t.arguments), "json")
            t.result?.let { CodeBlock(it.take(30_000), if (t.isError) "error" else "output") }
        }
    }
}

/** A command and its output, shaped like a terminal. */
@Composable
fun TerminalBlock(command: String, output: String?) {
    val c = Harness.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.codeBackground).border(1.dp, c.codeBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Text("$ ", style = Harness.type.mono.copy(fontWeight = FontWeight.SemiBold), color = c.ember)
            Text(command, style = Harness.type.mono, color = c.label, softWrap = false)
        }
        if (!output.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    output.take(20_000).trimEnd(), style = Harness.type.monoSmall, color = c.secondaryLabel, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun prettyJson(raw: String): String = runCatching {
    kotlinx.serialization.json.Json { prettyPrint = true }
        .encodeToString(kotlinx.serialization.json.JsonElement.serializer(), HarnessJson.parseToJsonElement(raw))
}.getOrDefault(raw)

// ---- File changes -----------------------------------------------------------------------------

private data class DiffLine(val kind: Char, val text: String)

private fun fileChange(t: ChatItem.Tool): Triple<String, List<DiffLine>, Boolean> {
    val o = json(t.arguments)
    val path = o?.s("path") ?: o?.s("file_path") ?: ""
    fun lines(s: String?) = s?.trimEnd('\n')?.split('\n').orEmpty()
    return when (t.name) {
        "write" -> {
            val append = (o?.get("append") as? JsonPrimitive)?.content == "true"
            Triple(path, lines(o?.s("content")).map { DiffLine('+', it) }, !append)
        }
        "edit" -> Triple(path, lines(o?.s("old_string")).map { DiffLine('-', it) } + lines(o?.s("new_string")).map { DiffLine('+', it) }, false)
        "multiedit" -> {
            val edits = (o?.get("edits") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val out = ArrayList<DiffLine>()
            edits.forEachIndexed { i, e ->
                if (i > 0) out += DiffLine('@', "edit ${i + 1}")
                out += lines(e.s("old_string")).map { DiffLine('-', it) }
                out += lines(e.s("new_string")).map { DiffLine('+', it) }
            }
            Triple(path, out, false)
        }
        else -> { // patch
            val p = o?.s("patch").orEmpty()
            val files = Regex("(?m)^\\*\\*\\* (?:Add|Update|Delete) File:\\s*(.+)$").findAll(p).map { it.groupValues[1].trim() }.toList()
            val out = p.lines().filter { !it.startsWith("*** Begin") && !it.startsWith("*** End") }.map { l ->
                when {
                    l.startsWith("***") -> DiffLine('@', l.removePrefix("***").trim())
                    l.startsWith("@@") -> DiffLine('@', l)
                    l.startsWith("+") -> DiffLine('+', l.drop(1))
                    l.startsWith("-") -> DiffLine('-', l.drop(1))
                    else -> DiffLine(' ', l.removePrefix(" "))
                }
            }
            Triple(files.joinToString(", ").ifEmpty { "patch" }, out, false)
        }
    }
}

/**
 * A file the agent is writing or editing: name, +/- stat, and the diff. While the call runs the
 * diff types itself in line by line under a sweeping progress bar.
 */
@Composable
fun FileChangeCard(t: ChatItem.Tool, onOpen: (String) -> Unit) {
    val c = Harness.colors
    val (path, diff, created) = remember(t.arguments) { fileChange(t) }
    val adds = diff.count { it.kind == '+' }
    val dels = diff.count { it.kind == '-' }
    var expanded by rememberSaveable(t.key) { mutableStateOf(false) }
    val name = path.substringAfterLast('/').ifEmpty { path }
    val dir = path.substringBeforeLast('/', "").let { if (it.length > 34) "…" + it.takeLast(33) else it }
    val border by animateColorAsState(if (t.isError) c.red.copy(alpha = 0.5f) else if (t.running) c.accent.copy(alpha = 0.45f) else c.codeBorder, label = "fcBorder")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, border, RoundedCornerShape(18.dp)).animateContentSize(dev.harness.ui.components.FaraiMotion.spatial()),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
                Icon(if (created) Icons.Rounded.NoteAdd else Icons.Rounded.EditNote, null, tint = c.label, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val verbText = when { t.running && created -> tr("Writing"); t.running -> tr("Editing"); t.isError -> tr("Failed"); created -> tr("Created"); else -> tr("Edited") }
                    if (t.running) ShimmerText(verbText, Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), c.secondaryLabel, c.label)
                    else Text(verbText, style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (t.isError) c.red else c.secondaryLabel)
                    Spacer(Modifier.width(8.dp))
                    if (adds > 0) Text("+$adds", style = Harness.type.numeric, color = c.diffAdd)
                    if (dels > 0) { Spacer(Modifier.width(6.dp)); Text("−$dels", style = Harness.type.numeric, color = c.diffDel) }
                }
                Text(name, style = Harness.type.subhead.copy(fontWeight = FontWeight.SemiBold, fontFamily = dev.harness.ui.theme.GeistMono), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dir.isNotEmpty()) Text(dir, style = Harness.type.monoSmall, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!t.running && !t.isError && path.isNotEmpty() && t.name != "patch") {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { onOpen(path) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, tr("Open"), tint = c.secondaryLabel, modifier = Modifier.size(17.dp))
                }
            }
            StatusMark(t.running, t.isError)
            Spacer(Modifier.width(4.dp))
        }
        if (t.running) SweepBar()
        // Running: the diff types itself in. Done: a short preview, the rest behind a tap.
        val shown = when { t.running -> 14; expanded -> 400; else -> 6 }
        if (diff.isNotEmpty()) DiffView(diff.take(shown), typing = t.running)
        if (!t.running && diff.size > 6) {
            Text(
                if (expanded) tr("Show less") else trf("Show {0} more lines", diff.size - 6),
                style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 10.dp).clickableNoRipple { expanded = !expanded },
            )
        } else Spacer(Modifier.height(6.dp))
        if (t.isError && t.result != null) {
            Text(t.result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = Harness.type.footnote, color = c.red,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
    }
}

@Composable
private fun SweepBar() {
    val c = Harness.colors
    val tr = rememberInfiniteTransition(label = "sweepBar")
    val x by tr.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "x")
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
        val w = size.width
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.accent, c.ember, Color.Transparent), startX = (x - 0.25f) * w, endX = (x + 0.25f) * w))
    }
}

@Composable
private fun DiffView(lines: List<DiffLine>, typing: Boolean) {
    val c = Harness.colors
    var revealed by remember { mutableIntStateOf(if (typing) 0 else lines.size) }
    LaunchedEffect(typing, lines.size) {
        if (!typing) { revealed = lines.size; return@LaunchedEffect }
        while (revealed < lines.size) { delay(70); revealed++ }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(c.codeBackground)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            lines.forEachIndexed { i, l ->
                AnimatedVisibility(i < revealed, enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) {
                    val (bar, wash, fg) = when (l.kind) {
                        '+' -> Triple(c.diffAdd, c.diffAdd.copy(alpha = 0.07f), c.label)
                        '-' -> Triple(c.diffDel, c.diffDel.copy(alpha = 0.07f), c.secondaryLabel)
                        '@' -> Triple(Color.Transparent, c.accent.copy(alpha = 0.07f), c.accent)
                        else -> Triple(Color.Transparent, Color.Transparent, c.secondaryLabel)
                    }
                    Row(Modifier.background(wash).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(2.dp).height(19.dp).background(bar.copy(alpha = 0.7f)))
                        Text(
                            if (l.kind == '+' || l.kind == '-') l.kind.toString() else " ",
                            style = Harness.type.monoSmall, color = bar, modifier = Modifier.width(18.dp).padding(start = 6.dp),
                        )
                        Text(l.text.ifEmpty { " " }, style = Harness.type.monoSmall, color = fg, softWrap = false)
                    }
                }
            }
        }
    }
}

// ---- Subagents, notices, results --------------------------------------------------------------

@Composable
fun SubagentCard(t: ChatItem.Tool, onOpen: (String) -> Unit) {
    val c = Harness.colors
    val o = json(t.arguments)
    val agent = o?.s("agent") ?: "general"
    val desc = o?.s("description") ?: argSummary(t.name, t.arguments)
    var expanded by rememberSaveable(t.key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (t.running) c.accent.copy(alpha = 0.35f) else c.codeBorder, RoundedCornerShape(18.dp))
            .animateContentSize(dev.harness.ui.components.FaraiMotion.spatial()),
    ) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
                if (t.running) Spark(18.dp, live = true) else Icon(Icons.Rounded.AccountTree, null, tint = c.label, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Agent · ${agent.replaceFirstChar { it.uppercase() }}", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
                Text(desc, style = Harness.type.subhead.copy(fontWeight = FontWeight.Medium), color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (t.children.isNotEmpty()) Text("${t.children.size}", style = Harness.type.numeric, color = c.tertiaryLabel, modifier = Modifier.padding(end = 8.dp))
            StatusMark(t.running, t.isError)
        }
        if (t.running && t.liveText.isNotBlank() && !expanded) Box(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) { LiveTail(t.liveText) }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val rows = transcriptRows(t.children)
                rows.forEach { r ->
                    when (r) {
                        is TranscriptRow.Activity -> ActivityGroup(r.tools, r.key)
                        is TranscriptRow.Web -> WebTrace(r.tools, r.key)
                        is TranscriptRow.Item -> (r.item as? ChatItem.Tool)?.let { child ->
                            if (child.name in FileTools) FileChangeCard(child, onOpen) else ActivityGroup(listOf(child), child.key)
                        }
                    }
                }
                t.result?.takeIf { !t.running }?.let { MarkdownBlock(it.take(6000), c.secondaryLabel, Modifier.padding(horizontal = 10.dp), baseStyle = Harness.type.subhead) }
            }
        }
    }
}

@Composable
fun NoticeCard(text: String, isError: Boolean, markdown: Boolean) {
    val c = Harness.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
            .background(if (isError) c.red.copy(alpha = 0.08f) else c.surface)
            .border(1.dp, if (isError) c.red.copy(alpha = 0.3f) else c.codeBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Bolt, null, tint = if (isError) c.red else c.ember, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        MarkdownBlock(tr(text), if (isError) c.red else c.label, Modifier.weight(1f), enabled = markdown, baseStyle = Harness.type.subhead)
    }
}

@Composable
fun FinishedCard(summary: String, markdown: Boolean) {
    val c = Harness.colors
    var drawn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { drawn = true }
    val sweep by animateFloatAsState(if (drawn) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "ring")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(c.green.copy(alpha = 0.10f), c.green.copy(alpha = 0.03f))))
            .border(1.dp, c.green.copy(alpha = 0.28f), RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(c.green, -90f, 360f * sweep, false, style = Stroke(2.dp.toPx()))
                }
                if (sweep > 0.6f) Icon(Icons.Rounded.Check, null, tint = c.green, modifier = Modifier.size(16.dp).alpha(((sweep - 0.6f) / 0.4f).coerceIn(0f, 1f)))
            }
            Spacer(Modifier.width(10.dp))
            Text(tr("Task complete"), style = Harness.type.headline, color = c.green)
        }
        Spacer(Modifier.height(10.dp))
        MarkdownBlock(summary, c.label, enabled = markdown, baseStyle = Harness.type.subhead)
    }
}

@Composable
fun CompactedCard(summary: String, removed: Int, markdown: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val c = Harness.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(c.separator))
            Row(
                Modifier.padding(horizontal = 10.dp).clip(CircleShape).background(c.surfaceElevated).clickableNoRipple(onToggle).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Compress, null, tint = c.secondaryLabel, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(trf("Compacted {0} messages", removed), style = Harness.type.caption.copy(fontWeight = FontWeight.Medium), color = c.secondaryLabel)
            }
            Box(Modifier.weight(1f).height(1.dp).background(c.separator))
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column { Spacer(Modifier.height(10.dp)); NoticeCard(summary, false, markdown) }
        }
    }
}

/** Running-state pill shown above the composer ("2 working"). */
@Composable
fun LivePill(text: String, color: Color, onClick: () -> Unit) {
    val c = Harness.colors
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).clickable(onClick = onClick).padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, size = 6.dp)
        Text(text, style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (color == c.accent) c.accent else color)
    }
}



/** The white rounded card every answer sits in, so it reads cleanly over the sky. */
@Composable
fun Modifier.answerCard(): Modifier =
    // No card: the reply sits right on the sky, aligned with the activity rows (Farrel, 2026-11-06).
    this.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp)
