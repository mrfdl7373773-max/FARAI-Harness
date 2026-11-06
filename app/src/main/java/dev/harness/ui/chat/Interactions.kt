package dev.harness.ui.chat

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.animation.AnimatedVisibility
import dev.harness.ui.components.pressScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.harness.core.Question
import dev.harness.core.permission.Approval
import dev.harness.ui.components.CodeBlock
import dev.harness.ui.theme.Harness

/**
 * A permission question as a compact sheet: what the agent wants to do, a short preview, and
 * Deny / Allow, with "Always allow" for the session and an optional note that turns a denial
 * into guidance the agent follows instead of stopping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionDialog(conf: PendingConfirmation, onAnswer: (Approval) -> Unit) {
    val c = Harness.colors
    var note by remember(conf) { mutableStateOf("") }
    var noting by remember(conf) { mutableStateOf(false) }
    var expanded by remember(conf) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lower = conf.title.lowercase()
    val icon = when {
        "command" in lower || "bash" in lower || "run" in lower -> Icons.Rounded.Terminal
        "change" in lower || "edit" in lower || "write" in lower -> Icons.Rounded.EditNote
        "fetch" in lower || "web" in lower || "http" in lower -> Icons.Rounded.Language
        "same arguments" in conf.detail || "loop" in lower -> Icons.Rounded.Loop
        else -> Icons.Rounded.Shield
    }
    val lines = remember(conf) { conf.detail.trimEnd().lines() }
    ModalBottomSheet(
        onDismissRequest = { onAnswer(Approval.Deny()) },
        sheetState = sheet,
        containerColor = c.background,
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.ember.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = c.ember, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(conf.title, style = Harness.type.title3, color = c.label)
                    Text(tr("FARAI needs your OK to continue"), style = Harness.type.footnote, color = c.secondaryLabel)
                }
            }
            Spacer(Modifier.height(14.dp))
            // Preview: a few lines, the rest one tap away.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .border(1.dp, c.codeBorder, RoundedCornerShape(16.dp)),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(max = if (expanded) 320.dp else 132.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (expanded) conf.detail.trimEnd() else lines.take(6).joinToString("\n"),
                        style = Harness.type.mono, color = c.label,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                        softWrap = false,
                    )
                }
                if (lines.size > 6) {
                    Text(
                        if (expanded) tr("Show less") else trf("Show all {0} lines", lines.size),
                        style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    )
                }
            }
            AnimatedVisibility(noting) {
                Box(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                        .border(1.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (note.isEmpty()) Text(tr("Tell the agent what to do instead…"), style = Harness.type.subhead, color = c.tertiaryLabel)
                    BasicTextField(note, { note = it }, textStyle = Harness.type.subhead.copy(color = c.label), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(
                    if (note.isBlank()) tr("Deny") else tr("Send note"), Modifier.weight(1f),
                    container = c.red.copy(alpha = 0.10f), content = c.red,
                ) { onAnswer(Approval.Deny(note.trim().ifEmpty { null })) }
                if (note.isBlank()) SheetButton(tr("Allow"), Modifier.weight(1f), container = c.accent, content = Color.White) { onAnswer(Approval.Once) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (conf.canRemember && note.isBlank()) {
                    Row(
                        Modifier.clip(CircleShape).clickable { onAnswer(Approval.Always) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.DoneAll, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Always allow this session"), style = Harness.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                    }
                } else Spacer(Modifier.width(1.dp))
                Row(
                    Modifier.clip(CircleShape).clickable { noting = !noting }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.EditNote, null, tint = c.secondaryLabel, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (noting) tr("No note") else tr("Add note"), style = Harness.type.label, color = c.secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, modifier: Modifier, container: Color, content: Color, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.height(52.dp).pressScale(interaction).clip(CircleShape).background(container)
            .clickable(interaction, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Harness.type.headline, color = content) }
}

/** Questions from the agent: tap options (one or several), or type your own answer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(questions: List<Question>, onAnswer: (List<List<String>>?) -> Unit) {
    val c = Harness.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picked = remember(questions) { questions.map { mutableStateListOf<String>() } }
    val custom = remember(questions) { questions.map { mutableStateOf("") } }
    ModalBottomSheet(onDismissRequest = { onAnswer(null) }, sheetState = sheet, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(tr("The agent is asking"), style = Harness.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            questions.forEachIndexed { qi, q ->
                Spacer(Modifier.size(14.dp))
                if (q.header.isNotBlank()) Text(q.header.uppercase(), style = Harness.type.caption, color = c.secondaryLabel)
                Text(q.question, style = Harness.type.headline, color = c.label)
                Spacer(Modifier.size(8.dp))
                q.options.forEach { op ->
                    val on = op.label in picked[qi]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.accent.copy(alpha = 0.12f) else c.surfaceElevated)
                            .border(0.5.dp, if (on) c.accent else c.separator, RoundedCornerShape(12.dp))
                            .clickable {
                                if (q.multiple) { if (on) picked[qi].remove(op.label) else picked[qi].add(op.label) }
                                else { picked[qi].clear(); picked[qi].add(op.label) }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(if (q.multiple) RoundedCornerShape(5.dp) else CircleShape)
                                .background(if (on) c.accent else Color.Transparent)
                                .border(1.5.dp, if (on) c.accent else c.tertiaryLabel, if (q.multiple) RoundedCornerShape(5.dp) else CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(op.label, style = Harness.type.body, color = c.label)
                            if (op.description.isNotBlank()) Text(op.description, style = Harness.type.footnote, color = c.secondaryLabel)
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceElevated)
                        .border(0.5.dp, c.separator, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (custom[qi].value.isEmpty()) Text(tr("Type your own answer"), style = Harness.type.body, color = c.tertiaryLabel)
                    BasicTextField(custom[qi].value, { custom[qi].value = it }, textStyle = Harness.type.body.copy(color = c.label), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.size(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer(null) }) { Text(tr("Skip"), style = Harness.type.body, color = c.secondaryLabel) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(CircleShape).background(c.accent).clickable {
                        onAnswer(questions.indices.map { i -> picked[i].toList() + listOfNotNull(custom[i].value.trim().takeIf { it.isNotEmpty() }) })
                    }.padding(horizontal = 22.dp, vertical = 10.dp)
                ) { Text(tr("Send answer"), style = Harness.type.headline, color = Color.White) }
            }
        }
    }
}
