package dev.harness.ui.components

import dev.harness.ui.i18n.tr
import dev.harness.ui.components.easyLongPress
import dev.harness.ui.i18n.trf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.delay

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class ListItems(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

object Markdown {
    fun parse(src: String): List<MdBlock> {
        val lines = src.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            val fence = Regex("^(`{3,}|~{3,})\\s*([\\w+#.-]*)").find(t)
            when {
                fence != null -> {
                    flush()
                    val marker = fence.groupValues[1]
                    val lang = fence.groupValues[2]
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(marker)) { code.appendLine(lines[i]); i++ }
                    out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                }
                t.isEmpty() -> flush()
                Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); val lvl = t.takeWhile { it == '#' }.length; out += MdBlock.Heading(lvl, t.drop(lvl).trim()) }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += MdBlock.Rule }
                t.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.appendLine(lines[i].trim().removePrefix(">").trim()); i++ }
                    out += MdBlock.Quote(q.toString().trim()); continue
                }
                Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(t) -> {
                    flush()
                    val ordered = t.first().isDigit()
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val l = lines[i].trim()
                        val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").find(l)
                        if (m != null) items += m.groupValues[2]
                        else if (l.isNotEmpty() && items.isNotEmpty() && lines[i].startsWith("  ")) items[items.size - 1] = items.last() + " " + l
                        else break
                        i++
                    }
                    out += MdBlock.ListItems(ordered, items); continue
                }
                t.startsWith("|") -> {
                    flush()
                    val table = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        if (!Regex("^\\|[\\s:|-]+\\|?$").matches(lines[i].trim())) table.appendLine(lines[i].trim())
                        i++
                    }
                    out += MdBlock.Code("table", table.toString().trimEnd()); continue
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    fun inline(text: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
        val regex = Regex("(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(\\*[^*\\s][^*]*\\*)|(\\[[^\\]]+\\]\\([^)\\s]+\\))")
        var last = 0
        for (m in regex.findAll(text)) {
            append(text.substring(last, m.range.first))
            val v = m.value
            when {
                v.startsWith("`") -> withStyle(SpanStyle(fontFamily = dev.harness.ui.theme.GeistMono, background = codeBg, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)) { append("\u2009${v.trim('`')}\u2009") }
                v.startsWith("**") || v.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(v.substring(2, v.length - 2)) }
                v.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(v.substring(1, v.length - 1)) }
                v.startsWith("[") -> {
                    val label = v.substringAfter('[').substringBefore("](")
                    val url = v.substringAfter("](").removeSuffix(")")
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(label) }
                    pop()
                }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

    private inline fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
        pushStyle(style); block(); pop()
    }
}

@Composable
fun MarkdownBlock(text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, baseStyle: TextStyle = Harness.type.body, selectable: Boolean = true) {
    if (!enabled) {
        if (selectable) SelectionContainer { Text(text, style = baseStyle, color = color, modifier = modifier) }
        else Text(text, style = baseStyle, color = color, modifier = modifier)
        return
    }
    val c = Harness.colors
    val blocks = remember(text) { Markdown.parse(text) }
    val uri = LocalUriHandler.current
    val codeBg = c.surfaceElevated
    MaybeSelectable(selectable, modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Heading -> Text(
                    Markdown.inline(b.text, c.accent, codeBg),
                    style = when (b.level) { 1 -> Harness.type.title2; 2 -> Harness.type.title3; else -> Harness.type.headline },
                    color = color,
                    modifier = Modifier.padding(top = if (b.level <= 2) 6.dp else 2.dp),
                )
                is MdBlock.Paragraph -> {
                    val ann = Markdown.inline(b.text, c.accent, codeBg)
                    ClickableText(ann, style = baseStyle.copy(color = color)) { off ->
                        ann.getStringAnnotations("URL", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } }
                    }
                }
                is MdBlock.Code -> if (b.lang == "table") MdTable(b.code, color, baseStyle) else CodeBlock(b.code, b.lang)
                is MdBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.items.forEachIndexed { i, raw ->
                        val task = Regex("^\\[([ xX])]\\s+").find(raw)
                        val item = if (task != null) raw.substring(task.range.last + 1) else raw
                        Row {
                            when {
                                task != null -> {
                                    val done = task.groupValues[1] != " "
                                    Box(
                                        Modifier.padding(top = 3.dp, end = 10.dp).size(17.dp).clip(RoundedCornerShape(5.dp))
                                            .background(if (done) c.accent else Color.Transparent)
                                            .border(1.5.dp, if (done) c.accent else c.tertiaryLabel, RoundedCornerShape(5.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { if (done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                                }
                                b.ordered -> Text("${i + 1}.", style = baseStyle.copy(fontFeatureSettings = "tnum"), color = c.secondaryLabel, modifier = Modifier.width(24.dp))
                                else -> Box(Modifier.width(22.dp).padding(top = 9.dp, start = 4.dp)) { Box(Modifier.size(5.dp).clip(CircleShape).background(c.secondaryLabel)) }
                            }
                            Text(Markdown.inline(item, c.accent, codeBg), style = baseStyle, color = if (task?.groupValues?.get(1)?.isNotBlank() == true) c.secondaryLabel else color)
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(12.dp))
                    Text(Markdown.inline(b.text, c.accent, codeBg), style = baseStyle, color = c.secondaryLabel)
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.separator))
            }
        }
    }
    }
}

@Composable
private fun MaybeSelectable(selectable: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (selectable) SelectionContainer(modifier) { content() } else Box(modifier) { content() }
}

/** Pipe table drawn as a real grid: header row tinted, cells hairlined, scrolls sideways when wide. */
@Composable
private fun MdTable(src: String, color: Color, base: TextStyle) {
    val c = Harness.colors
    val rows = remember(src) {
        src.lines().filter { it.isNotBlank() }.map { l -> l.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
    }
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.codeBorder, RoundedCornerShape(14.dp))) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (ci in 0 until cols) {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    rows.forEachIndexed { ri, r ->
                        Box(
                            Modifier.fillMaxWidth().background(if (ri == 0) c.surfaceElevated.copy(alpha = 0.6f) else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                Markdown.inline(r.getOrElse(ci) { "" }, c.accent, c.surfaceElevated),
                                style = (if (ri == 0) base.copy(fontWeight = FontWeight.SemiBold) else base).copy(fontSize = Harness.type.subhead.fontSize, lineHeight = Harness.type.subhead.lineHeight),
                                color = color,
                                softWrap = false,
                            )
                        }
                        if (ri < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.codeBorder))
                    }
                }
            }
        }
    }
}

/** Code block: language label, copy, line-safe horizontal scroll, quiet syntax colour. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CodeBlock(code: String, lang: String = "", modifier: Modifier = Modifier) {
    val c = Harness.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val highlighted = remember(code, lang, c.isDark) { Syntax.highlight(code, lang, c) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.codeBackground).border(1.dp, c.codeBorder, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifEmpty { "code" }, style = Harness.type.caption.copy(fontFamily = dev.harness.ui.theme.GeistMono), color = c.tertiaryLabel, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(c.surfaceElevated).clickable { clipboard.setText(AnnotatedString(code)); copied = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.animation.AnimatedContent(copied, label = "copy") { done ->
                    Icon(if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, tint = if (done) c.green else c.secondaryLabel, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(if (copied) tr("Copied") else tr("Copy"), style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (copied) c.green else c.secondaryLabel)
            }
        }
        var menu by remember { mutableStateOf(false) }
        var press by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var selectAll by remember { mutableStateOf(false) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        Box {
            Text(
                highlighted,
                style = Harness.type.mono,
                color = c.label,
                modifier = Modifier.fillMaxWidth()
                    .easyLongPress(delayMs = 290L) { at -> press = at; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }
                    .horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                softWrap = false,
            )
            dev.harness.ui.chat.MessageMenu(menu, code, onDismiss = { menu = false }, onSelectAll = { menu = false; selectAll = true }, press = press)
        }
        if (selectAll) dev.harness.ui.chat.SelectAllSheet(code) { selectAll = false }
    }
}

/** A small, forgiving highlighter: keywords, strings, comments, numbers, for the usual languages. */
object Syntax {
    private val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package",
        "private", "public", "internal", "override", "suspend", "data", "sealed", "enum", "const", "true", "false", "null", "this",
        "def", "lambda", "None", "True", "False", "elif", "in", "not", "and", "or", "async", "await", "from", "as", "with", "try",
        "except", "catch", "finally", "throw", "raise", "new", "function", "let", "export", "default", "type", "struct", "impl",
        "pub", "fn", "mut", "match", "use", "mod", "go", "func", "static", "void", "int", "string", "bool", "echo", "then", "fi", "do", "done",
    )
    private val token = Regex(
        "(//[^\\n]*|#(?![!{])[^\\n]*|/\\*[\\s\\S]*?\\*/)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`)|(\\b\\d+(?:\\.\\d+)?\\b)|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)"
    )

    fun highlight(code: String, lang: String, c: dev.harness.ui.theme.HarnessColors): AnnotatedString {
        val l = lang.lowercase()
        if (code.length > 20_000 || l in setOf("", "text", "output", "error", "table", "diff")) {
            if (l == "diff") return diff(code, c)
            return AnnotatedString(code)
        }
        val hashComments = l in setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "toml", "ruby", "rb", "r")
        return buildAnnotatedString {
            var last = 0
            for (m in token.findAll(code)) {
                append(code.substring(last, m.range.first))
                val v = m.value
                val style = when {
                    m.groups[1] != null && (v.startsWith("//") || v.startsWith("/*") || hashComments) -> SpanStyle(color = c.tertiaryLabel, fontStyle = FontStyle.Italic)
                    m.groups[1] != null -> null
                    m.groups[2] != null -> SpanStyle(color = c.green)
                    m.groups[3] != null -> SpanStyle(color = c.orange)
                    m.groups[4] != null && v in keywords -> SpanStyle(color = c.accent, fontWeight = FontWeight.Medium)
                    m.groups[4] != null && v.first().isUpperCase() -> SpanStyle(color = c.purple)
                    else -> null
                }
                if (style != null) { pushStyle(style); append(v); pop() } else append(v)
                last = m.range.last + 1
            }
            append(code.substring(last))
        }
    }

    fun diff(code: String, c: dev.harness.ui.theme.HarnessColors): AnnotatedString = buildAnnotatedString {
        code.lines().forEachIndexed { i, line ->
            if (i > 0) append("\n")
            val st = when {
                line.startsWith("+") && !line.startsWith("+++") -> SpanStyle(color = c.diffAdd, background = c.diffAdd.copy(alpha = 0.08f))
                line.startsWith("-") && !line.startsWith("---") -> SpanStyle(color = c.diffDel, background = c.diffDel.copy(alpha = 0.08f))
                line.startsWith("@@") -> SpanStyle(color = c.accent)
                else -> null
            }
            if (st != null) { pushStyle(st); append(line); pop() } else append(line)
        }
    }
}
