package dev.harness.ui.files

import dev.harness.ui.i18n.tr
import dev.harness.ui.components.easyLongPress
import dev.harness.ui.i18n.trf
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.combinedClickable

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.harness.core.files.ProducedFile
import dev.harness.tools.Workspace
import dev.harness.ui.components.BarIcon
import dev.harness.ui.components.NavBar
import dev.harness.ui.theme.Harness
import dev.harness.ui.theme.HarnessColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private val ARCHIVES = setOf("zip", "jar", "apk", "aar", "whl")
private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
private val WEB = setOf("html", "htm")
private const val MAX_TEXT_BYTES = 1_000_000L
private const val MAX_LINES = 5_000
private const val MAX_ROW = 400
/** Clipboard and the select sheet go through Binder; over ~1 MB it throws TransactionTooLargeException. */
private const val MAX_COPY_CHARS = 200_000

private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

private fun fileIcon(name: String, isDir: Boolean): ImageVector = when {
    isDir -> Icons.Rounded.Folder
    ext(name) in ARCHIVES -> Icons.Rounded.Archive
    ext(name) in IMAGES -> Icons.Rounded.Image
    ext(name) in WEB -> Icons.Rounded.Language
    ext(name) in setOf("md", "txt", "pdf", "csv", "log") -> Icons.Rounded.Description
    else -> Icons.Rounded.Code
}

private fun fileColor(name: String, isDir: Boolean, c: HarnessColors): Color = when {
    isDir -> c.accent
    ext(name) in ARCHIVES -> c.orange
    ext(name) in IMAGES -> c.pink
    ext(name) in WEB -> c.teal
    ext(name) in setOf("md", "txt", "pdf", "csv", "log") -> c.gray
    else -> c.indigo
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** The card under a finished turn: one row per file or folder the agent made. */
@Composable
fun ProducedFilesCard(files: List<ProducedFile>, onOpen: (String) -> Unit) {
    val c = Harness.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.codeBorder, RoundedCornerShape(18.dp)),
    ) {
        Text(
            if (files.size == 1) "1 file ready" else "${files.size} files ready",
            style = Harness.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.secondaryLabel,
            modifier = Modifier.padding(start = 14.dp, top = 11.dp, bottom = 2.dp),
        )
        files.forEachIndexed { i, f ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(c.separator))
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(f.path) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = f.display.trimEnd('/').substringAfterLast('/')
                FileBadge(name, f.isDirectory)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name + if (f.isDirectory) "/" else "", style = Harness.type.subhead, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val parent = f.display.trimEnd('/').substringBeforeLast('/', "")
                    val meta = listOfNotNull(parent.ifEmpty { null }, if (f.isDirectory) "folder" else humanSize(f.size)).joinToString(" · ")
                    Text(meta, style = Harness.type.caption, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.tertiaryLabel, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FileBadge(name: String, isDir: Boolean) {
    val c = Harness.colors
    val color = fileColor(name, isDir, c)
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) { Icon(fileIcon(name, isDir), null, tint = color, modifier = Modifier.size(17.dp)) }
}

private sealed interface Content {
    data object Loading : Content
    data class Missing(val reason: String) : Content
    data class Folder(val entries: List<File>) : Content
    data class Archive(val entries: List<Pair<String, Long>>, val total: Int) : Content
    /** [lines] are display rows; [numbers] holds the file line number for each row, 0 for a wrapped continuation. */
    data class Text(val lines: List<String>, val truncated: Boolean, val numbers: List<Int> = emptyList()) : Content
    data class Other(val size: Long) : Content
}

private fun load(f: File): Content {
    if (!f.exists()) return Content.Missing(tr("This file no longer exists."))
    if (f.isDirectory) {
        val kids = f.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        return Content.Folder(kids)
    }
    if (ext(f.name) in ARCHIVES) return runCatching {
        ZipFile(f).use { z ->
            val all = z.entries().toList()
            Content.Archive(all.take(1000).map { it.name to it.size }, all.size)
        }
    }.getOrElse { Content.Missing(trf("Could not read this archive: {0}", it.message)) }
    if (ext(f.name) in IMAGES - "svg" || ext(f.name) == "pdf" || Workspace.isProbablyBinary(f)) return Content.Other(f.length())
    val truncated = f.length() > MAX_TEXT_BYTES
    val text = f.inputStream().use { s -> String(s.readNBytesCompat(MAX_TEXT_BYTES.toInt()), Charsets.UTF_8) }
    val lines = text.lines()
    // A minified line can be hundreds of KB: laid out as one unwrapped row it is wider than Compose
    // can measure and the app crashes. Cut long lines into rows of MAX_ROW chars instead.
    val rows = ArrayList<String>(minOf(lines.size, MAX_LINES)); val numbers = ArrayList<Int>()
    var consumed = 0
    for ((i, line) in lines.withIndex()) {
        if (rows.size >= MAX_LINES) break
        consumed = i + 1
        val clean = line.replace("\t", "    ").replace('\u0000', ' ')
        if (clean.length <= MAX_ROW) { rows += clean; numbers += i + 1; continue }
        var at = 0
        while (at < clean.length && rows.size < MAX_LINES) {
            var end = minOf(at + MAX_ROW, clean.length)
            if (end < clean.length && Character.isHighSurrogate(clean[end - 1])) end--
            rows += clean.substring(at, end); numbers += if (at == 0) i + 1 else 0
            at = end
        }
    }
    return Content.Text(rows, truncated || consumed < lines.size || rows.size >= MAX_LINES, numbers)
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var left = max
    while (left > 0) {
        val n = read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n); left -= n
    }
    return out.toByteArray()
}

/** Opens a file, folder or zip the agent made: code with line numbers, a folder listing, or a zip's contents. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun FileViewerScreen(path: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Harness.colors
    val context = LocalContext.current
    val file = File(path)
    val content by produceState<Content>(Content.Loading, path) { value = withContext(Dispatchers.IO) { runCatching { load(file) }.getOrElse { Content.Missing(it.message ?: tr("Could not open")) } } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1400); copied = false } }
    var lineMenu by remember { mutableStateOf(-1) }
    var linePress by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var selectAll by remember { mutableStateOf(false) }
    val allText = remember(content) {
        (content as? Content.Text)?.let { t ->
            val sb = StringBuilder()
            for ((i, row) in t.lines.withIndex()) {
                if (i > 0 && (t.numbers.getOrNull(i) ?: 1) > 0) sb.append('\n')
                sb.append(row)
                if (sb.length > MAX_COPY_CHARS) break
            }
            sb.toString().take(MAX_COPY_CHARS)
        }.orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        NavBar(
            title = file.name.ifEmpty { path },
            subtitle = file.parentFile?.name,
            backLabel = tr("Back"),
            onBack = onBack,
            actions = {
                if (file.isFile) {
                    if (content is Content.Text) BarIcon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, tr("Copy file")) {
                        runCatching { clipboard.setText(androidx.compose.ui.text.AnnotatedString(allText)) }
                            .onSuccess {
                                copied = true
                                if (file.length() > MAX_COPY_CHARS) Toast.makeText(context, tr("File is long; copied the first part."), Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, trf("Could not copy: {0}", it.message), Toast.LENGTH_SHORT).show() }
                    }
                    Box {
                        var more by remember { mutableStateOf(false) }
                        BarIcon(Icons.Rounded.MoreHoriz, tr("More")) { more = true }
                        androidx.compose.material3.DropdownMenu(more, { more = false }, modifier = Modifier.background(c.surface)) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("Open with"), style = Harness.type.body, color = c.label) },
                                leadingIcon = { Icon(Icons.Rounded.OpenInNew, null, tint = c.label) },
                                onClick = { more = false; openWith(context, file) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("Share"), style = Harness.type.body, color = c.label) },
                                leadingIcon = { Icon(Icons.Rounded.Share, null, tint = c.label) },
                                onClick = { more = false; shareFile(context, file) },
                            )
                        }
                    }
                }
            },
        )
        if (selectAll) dev.harness.ui.chat.SelectAllSheet(allText.take(50_000)) { selectAll = false }
        when (val s = content) {
            Content.Loading -> Unit
            is Content.Missing -> Message(s.reason)
            is Content.Folder -> if (s.entries.isEmpty()) Message(tr("This folder is empty.")) else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(s.entries) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(f.absolutePath) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileBadge(f.name, f.isDirectory)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, style = Harness.type.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (f.isDirectory) "" else humanSize(f.length()), style = Harness.type.footnote, color = c.secondaryLabel)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.tertiaryLabel, modifier = Modifier.size(18.dp))
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 56.dp))
                }
            }
            is Content.Archive -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                item {
                    Text(
                        "${s.total} entries · ${humanSize(file.length())}" + if (s.total > s.entries.size) " · first ${s.entries.size} shown" else "",
                        style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(16.dp, 12.dp),
                    )
                }
                items(s.entries) { (name, size) ->
                    val isDir = name.endsWith("/")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(name.trimEnd('/').substringAfterLast('/'), isDir)
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = Harness.type.monoSmall, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (!isDir && size >= 0) Text(humanSize(size), style = Harness.type.caption, color = c.secondaryLabel)
                    }
                }
            }
            is Content.Text -> {
                val gutter = (s.numbers.maxOrNull() ?: s.lines.size).toString().length
                LazyColumn(Modifier.fillMaxSize().background(c.codeBackground).horizontalScroll(rememberScrollState()).navigationBarsPadding()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(s.lines.size) { i ->
                        Row {
                            Text(
                                (s.numbers.getOrNull(i)?.let { n -> if (n > 0) n.toString() else "" } ?: (i + 1).toString()).padStart(gutter), style = Harness.type.monoSmall, color = c.tertiaryLabel,
                                modifier = Modifier.padding(start = 10.dp, end = 12.dp),
                            )
                            Box {
                                val lang = file.extension.lowercase().let { e -> mapOf("kt" to "kotlin", "py" to "python", "js" to "javascript", "ts" to "typescript", "sh" to "bash", "json" to "json", "java" to "java", "go" to "go", "rs" to "rust", "c" to "c", "cpp" to "cpp", "yml" to "yaml", "yaml" to "yaml", "toml" to "toml", "rb" to "ruby", "diff" to "diff", "patch" to "diff")[e] ?: "" }
                                val hl = remember(i, s.lines[i]) {
                                    runCatching { dev.harness.ui.components.Syntax.highlight(s.lines[i], lang, c) }
                                        .getOrElse { androidx.compose.ui.text.AnnotatedString(s.lines[i]) }
                                }
                                Text(
                                    hl, style = Harness.type.monoSmall, color = c.label, softWrap = false,
                                    modifier = Modifier.easyLongPress(delayMs = 290L) { at -> linePress = at; lineMenu = i }.padding(end = 16.dp),
                                )
                                dev.harness.ui.chat.MessageMenu(lineMenu == i, s.lines[i], onDismiss = { lineMenu = -1 }, onSelectAll = { lineMenu = -1; selectAll = true }, press = linePress)
                            }
                        }
                    }
                    if (s.truncated) item {
                        Text(tr("File is long; only the first part is shown. Use Open with to see all of it."), style = Harness.type.caption, color = c.secondaryLabel, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            is Content.Other -> Column(
                Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FileBadge(file.name, false)
                Spacer(Modifier.height(12.dp))
                Text(humanSize(s.size), style = Harness.type.footnote, color = c.secondaryLabel)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Open with another app", style = Harness.type.headline, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { openWith(context, file) }.padding(12.dp, 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Harness.type.subhead, color = Harness.colors.secondaryLabel)
    }
}

private fun uriFor(context: Context, f: File) = FileProvider.getUriForFile(context, context.packageName + ".files", f)

private fun mimeFor(f: File): String = when (ext(f.name)) {
    "md", "py", "kt", "kts", "js", "ts", "json", "yaml", "yml", "toml", "sh", "csv", "log" -> "text/plain"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f.name)) ?: "*/*"
}

fun openWith(context: Context, f: File) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, f), mimeFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Open ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        val msg = if (it is ActivityNotFoundException) tr("No app can open this file") else trf("Could not open: {0}", it.message)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(context: Context, f: File) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeFor(f)).putExtra(Intent.EXTRA_STREAM, uriFor(context, f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, trf("Could not share: {0}", it.message), Toast.LENGTH_SHORT).show() }
}
