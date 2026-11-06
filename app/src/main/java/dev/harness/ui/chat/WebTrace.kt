package dev.harness.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.harness.core.search.SearchTrace
import dev.harness.net.HarnessJson
import dev.harness.ui.components.FaraiMotion
import dev.harness.ui.components.ShimmerText
import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Tools whose calls the transcript draws as one web research trace. */
val WebTools = setOf("web_search", "web_fetch")

private fun arg(raw: String, key: String): String? =
    runCatching { (HarnessJson.parseToJsonElement(raw).jsonObject[key] as? JsonPrimitive)?.contentOrNull }.getOrNull()

private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

/**
 * A run of web searches and page reads, after Beautiful UI's search trace
 * (github.com/slev12397/beautiful-ui, MIT): a globe and a shimmering "Searching the web" that
 * settles to "Searched the web"; underneath, on a thin rail, each query and the sources it
 * found, arriving one by one. Open while it works, folded once it is done, tap to reopen.
 */
@Composable
fun WebTrace(tools: List<ChatItem.Tool>, key: String) {
    val c = Harness.colors
    val running = tools.any { it.running }
    val searches = tools.filter { it.name == "web_search" }
    val reads = tools.filter { it.name == "web_fetch" }
    // Only a trace that was live when it appeared animates; history opens settled.
    val wasLive = rememberSaveable(key) { running }
    var manual by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    val expanded = manual ?: running
    val rot by animateFloatAsState(if (expanded) 180f else 0f, FaraiMotion.spatial(), label = "chev")

    val liveTool = tools.lastOrNull { it.running }
    val active = when {
        liveTool?.name == "web_fetch" -> fetchUrls(liveTool.arguments).let { u ->
            if (u.size > 1) trf("Reading {0} pages", u.size) else trf("Reading {0}", hostOf(u.firstOrNull().orEmpty()).ifBlank { tr("page") })
        }
        liveTool != null && (arg(liveTool.arguments, "read")?.toIntOrNull() ?: 0) > 0 -> tr("Searching and reading")
        else -> tr("Searching the web")
    }
    val pageTotal = remember(tools.map { it.result }) { tools.filter { !it.running }.sumOf { pageCount(it) } }
    val failedAll = !running && tools.all { it.isError }
    val done = when {
        failedAll -> tr("Search failed")
        searches.size > 1 && pageTotal > 0 -> trf("Searched {0} queries · read {1}", searches.size, pages(pageTotal))
        searches.size > 1 -> trf("Searched {0} queries", searches.size)
        searches.isNotEmpty() && pageTotal > 0 -> trf("Searched the web · read {0}", pages(pageTotal))
        searches.isNotEmpty() -> tr("Searched the web")
        else -> trf("Read {0}", pages(pageTotal.coerceAtLeast(reads.size)))
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).animateContentSize(FaraiMotion.spatial())) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { manual = !expanded }.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Language, null, tint = if (running) c.secondaryLabel else c.tertiaryLabel, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            AnimatedContent(running, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) }, label = "head") { live ->
                if (live) ShimmerText(active, Harness.type.footnote.copy(fontWeight = FontWeight.Medium), c.tertiaryLabel, c.label)
                else Text(
                    done, style = Harness.type.footnote.copy(fontWeight = FontWeight.Medium),
                    color = if (failedAll) c.red else c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, if (expanded) tr("Collapse") else tr("Expand"), tint = c.tertiaryLabel, modifier = Modifier.size(16.dp).rotate(rot))
        }

        AnimatedVisibility(expanded, enter = fadeIn(tween(300)) + expandVertically(FaraiMotion.spatial()), exit = fadeOut(tween(200)) + shrinkVertically(FaraiMotion.spatial())) {
            val rail = c.rail
            Column(
                Modifier.padding(start = 11.dp, top = 2.dp, bottom = 4.dp)
                    .drawBehind { drawLine(rail, Offset(3.dp.toPx(), -6.dp.toPx()), Offset(3.dp.toPx(), size.height - 4.dp.toPx()), strokeWidth = 1.dp.toPx()) }
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tools.forEach { t ->
                    if (t.name == "web_search") SearchBlock(t, wasLive) else ReadRow(t, wasLive)
                }
            }
        }
    }
}

private fun pages(n: Int) = if (n == 1) tr("1 page") else trf("{0} pages", n)

@Composable
private fun SearchBlock(t: ChatItem.Tool, animate: Boolean) {
    val c = Harness.colors
    val query = arg(t.arguments, "query").orEmpty()
    val news = arg(t.arguments, "type") == "news"
    val trace = remember(t.result, t.arguments) { SearchTrace.parse(query, t.result) }
    var showAll by rememberSaveable(t.key) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 26.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (news) Icons.Rounded.Newspaper else Icons.Rounded.Search, null, tint = c.tertiaryLabel, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(query, style = Harness.type.footnote, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            arg(t.arguments, "time_range")?.let {
                Spacer(Modifier.width(6.dp))
                Text(trf("past {0}", tr(it)), style = Harness.type.caption, color = c.tertiaryLabel)
            }
        }
        when {
            t.running && trace.sources.isEmpty() -> SkeletonRows()
            t.isError -> Text(
                t.result.orEmpty().removePrefix("ERROR: ").lineSequence().first().take(160), style = Harness.type.caption, color = c.red,
                modifier = Modifier.padding(start = 28.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
            )
            else -> {
                val limit = if (showAll) trace.sources.size else minOf(4, trace.sources.size)
                trace.sources.take(limit).forEachIndexed { i, s -> SourceRow(s, i, animate) }
                val more = trace.sources.size - limit
                if (more > 0) Text(
                    trf("+{0} more", more), style = Harness.type.caption, color = c.tertiaryLabel,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(6.dp)).clickable { showAll = true }.padding(horizontal = 4.dp, vertical = 3.dp),
                )
                trace.pages.forEachIndexed { i, p -> PageRow(p.title, p.url, p.domain, running = false, error = p.error, index = limit + i, animate = animate) }
                val ok = trace.engines.filter { it.ok }
                if (ok.isNotEmpty()) Text(
                    buildString {
                        append(trf("via {0}", ok.joinToString(", ") { SearchTrace.engineLabel(it.name) }))
                        if (trace.cached) append(" · ").append(tr("cached")) else trace.seconds?.let { append(" · ").append(it).append("s") }
                    },
                    style = Harness.type.caption, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, top = 1.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** One source: a coloured globe dot, the page title (underlined while pressed) and its domain. Tap opens it. */
@Composable
private fun SourceRow(s: SearchTrace.Source, index: Int, animate: Boolean) {
    val c = Harness.colors
    val uri = LocalUriHandler.current
    var shown by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { if (!shown) { delay(index * 120L); shown = true } }
    val tones = listOf(c.accent, c.ember, c.green)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    AnimatedVisibility(shown, enter = fadeIn(tween(320)) + slideInVertically(FaraiMotion.spatial()) { it / 2 }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(8.dp))
                .clickable(interaction, androidx.compose.material.ripple.rememberRipple()) { runCatching { uri.openUri(s.url) } }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(15.dp).clip(CircleShape).background(tones[index % tones.size]), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Language, null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                s.title, style = Harness.type.footnote.copy(fontWeight = FontWeight.Medium, textDecoration = if (pressed) TextDecoration.Underline else null),
                color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(s.domain, style = Harness.type.caption, color = c.tertiaryLabel, maxLines = 1)
        }
    }
}

/** URLs a web_fetch call asked for (`url` and/or `urls`). */
private fun fetchUrls(raw: String): List<String> = runCatching {
    val o = HarnessJson.parseToJsonElement(raw).jsonObject
    buildList {
        (o["url"] as? JsonPrimitive)?.contentOrNull?.let { add(it) }
        (o["urls"] as? kotlinx.serialization.json.JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(::add) }
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}.getOrDefault(emptyList())

/** How many pages a web tool call reads. */
private fun pageCount(t: ChatItem.Tool): Int = when (t.name) {
    "web_fetch" -> fetchUrls(t.arguments).size.coerceAtLeast(1)
    else -> SearchTrace.parse("", t.result).pages.size
}

/** Pages the agent opened with web_fetch: one row each, read in parallel. */
@Composable
private fun ReadRow(t: ChatItem.Tool, animate: Boolean) {
    val urls = remember(t.arguments) { fetchUrls(t.arguments) }
    val done = remember(t.result) { SearchTrace.fetchedPages(t.result) }
    Column(Modifier.fillMaxWidth()) {
        when {
            t.running || done.isEmpty() -> urls.forEachIndexed { i, u ->
                PageRow(null, u, hostOf(u), running = t.running, error = !t.running && t.isError, index = i, animate = animate)
            }
            else -> done.forEachIndexed { i, p -> PageRow(p.title, p.url, p.domain, running = false, error = p.error, index = i, animate = animate) }
        }
    }
}

@Composable
private fun PageRow(title: String?, url: String, domain: String, running: Boolean, error: Boolean, index: Int, animate: Boolean) {
    val c = Harness.colors
    val uri = LocalUriHandler.current
    var shown by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { if (!shown) { delay(index * 90L); shown = true } }
    AnimatedVisibility(shown, enter = fadeIn(tween(320)) + slideInVertically(FaraiMotion.spatial()) { it / 2 }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(url) } }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(15.dp).clip(CircleShape).background(if (error) c.red else c.tertiaryLabel.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Description, null, tint = Color.White, modifier = Modifier.size(9.dp))
            }
            Spacer(Modifier.width(8.dp))
            val verb = when { running -> tr("Reading"); error -> tr("Couldn't read"); else -> tr("Read") }
            Text(verb, style = Harness.type.footnote, color = c.secondaryLabel)
            Spacer(Modifier.width(6.dp))
            val label = title ?: domain
            if (running) ShimmerText(label, Harness.type.footnote.copy(fontWeight = FontWeight.Medium), c.tertiaryLabel, c.label, Modifier.weight(1f, fill = false))
            else Text(label, style = Harness.type.footnote.copy(fontWeight = FontWeight.Medium), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (title != null) {
                Spacer(Modifier.width(8.dp))
                Text(domain, style = Harness.type.caption, color = c.tertiaryLabel, maxLines = 1)
            }
        }
    }
}

/** Three soft bars while the first results are on their way. */
@Composable
private fun SkeletonRows() {
    val c = Harness.colors
    Column(Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        listOf(0.72f, 0.55f, 0.64f).forEachIndexed { i, w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(15.dp).clip(CircleShape).background(c.tertiaryLabel.copy(alpha = 0.18f)))
                Spacer(Modifier.width(8.dp))
                ShimmerBar(w, i)
            }
        }
    }
}

@Composable
private fun ShimmerBar(fraction: Float, index: Int) {
    val c = Harness.colors
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(
        0.10f, 0.24f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900, delayMillis = index * 120), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "a",
    )
    Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.tertiaryLabel.copy(alpha = a)))
}
