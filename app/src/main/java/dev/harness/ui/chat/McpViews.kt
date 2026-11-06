package dev.harness.ui.chat

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.harness.core.mcp.ElicitAnswer
import dev.harness.core.mcp.ElicitField
import dev.harness.core.mcp.Elicitation
import dev.harness.core.mcp.McpInteraction
import dev.harness.core.mcp.McpManager
import dev.harness.core.mcp.SamplingDecision
import dev.harness.ui.theme.Harness

/**
 * Shows whatever an MCP server is waiting on the user for, on any screen: a form (elicitation),
 * a page to open (URL elicitation), or permission to use the model (sampling). One at a time.
 */
@Composable
fun McpInteractionHost(mcp: McpManager) {
    val pending by mcp.interactions.collectAsState()
    when (val i = pending.firstOrNull()) {
        is McpInteraction.Form -> ElicitationSheet(i,
            onSubmit = { mcp.answerElicitation(i.id, ElicitAnswer("accept", it)) },
            onDecline = { mcp.answerElicitation(i.id, ElicitAnswer.DECLINE) },
            onCancel = { mcp.answerElicitation(i.id, ElicitAnswer.CANCEL) })
        is McpInteraction.Url -> UrlElicitationDialog(i,
            onOpen = { mcp.answerElicitation(i.id, ElicitAnswer("accept")) },
            onDecline = { mcp.answerElicitation(i.id, ElicitAnswer.DECLINE) },
            onCancel = { mcp.answerElicitation(i.id, ElicitAnswer.CANCEL) })
        is McpInteraction.Sampling -> SamplingDialog(i) { mcp.answerSampling(i.id, it) }
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ElicitationSheet(
    form: McpInteraction.Form,
    onSubmit: (Map<String, kotlinx.serialization.json.JsonElement>) -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Harness.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val values = remember(form.id) {
        mutableStateMapOf<String, String>().apply {
            form.fields.forEach { f -> put(f.key, if (f.kind == "boolean") Elicitation.boolDefault(f) else f.default.orEmpty()) }
        }
    }
    var showErrors by remember(form.id) { mutableStateOf(false) }
    val errors = form.fields.associate { f -> f.key to Elicitation.validate(f, values[f.key].orEmpty()) }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("${form.server.uppercase()} · MCP", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            Spacer(Modifier.size(4.dp))
            Text(form.message.ifBlank { tr("The server needs some details.") }, style = Harness.type.headline, color = c.label)
            Spacer(Modifier.size(4.dp))
            Text(tr("Only share what you are comfortable sending to this server."), style = Harness.type.footnote, color = c.secondaryLabel)

            form.fields.forEach { f ->
                Spacer(Modifier.size(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.title, style = Harness.type.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.label, modifier = Modifier.weight(1f))
                    if (f.kind == "boolean") {
                        Switch(
                            checked = values[f.key] == "true",
                            onCheckedChange = { values[f.key] = it.toString() },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                        )
                    } else if (f.required) Text("required", style = Harness.type.caption, color = c.tertiaryLabel)
                }
                if (f.description.isNotBlank()) Text(f.description, style = Harness.type.footnote, color = c.secondaryLabel)
                Spacer(Modifier.size(6.dp))
                when (f.kind) {
                    "boolean" -> Unit
                    "enum", "multi" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chosen = values[f.key].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                        f.options.forEach { (value, label) ->
                            val on = value in chosen
                            Box(
                                Modifier.clip(CircleShape)
                                    .background(if (on) c.accent.copy(alpha = 0.14f) else c.surfaceElevated)
                                    .border(1.dp, if (on) c.accent else c.separator, CircleShape)
                                    .clickable {
                                        values[f.key] = if (f.kind == "multi") {
                                            (if (on) chosen - value else chosen + value).joinToString(",")
                                        } else if (on && !f.required) "" else value
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) { Text(label, style = Harness.type.body, color = if (on) c.accent else c.label) }
                        }
                    }
                    else -> FieldBox(
                        value = values[f.key].orEmpty(),
                        onChange = { values[f.key] = it },
                        placeholder = placeholder(f),
                        keyboard = when {
                            f.kind == "integer" -> KeyboardType.Number
                            f.kind == "number" -> KeyboardType.Decimal
                            f.format == "email" -> KeyboardType.Email
                            f.format == "uri" || f.format == "url" -> KeyboardType.Uri
                            isSecret(f) -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                        secret = isSecret(f),
                        error = showErrors && errors[f.key] != null,
                    )
                }
                if (showErrors) errors[f.key]?.let { Text(it, style = Harness.type.caption, color = c.red, modifier = Modifier.padding(top = 4.dp)) }
            }

            Spacer(Modifier.size(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDecline) { Text(tr("Decline"), style = Harness.type.body, color = c.secondaryLabel) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(CircleShape).background(c.accent).clickable {
                        if (errors.values.any { it != null }) showErrors = true
                        else onSubmit(Elicitation.content(form.fields, values.toMap()))
                    }.padding(horizontal = 22.dp, vertical = 10.dp)
                ) { Text(tr("Send"), style = Harness.type.headline, color = Color.White) }
            }
        }
    }
}

private fun isSecret(f: ElicitField) = f.format == "password" || Regex("(?i)password|secret|token|api[_ ]?key|pin").containsMatchIn(f.key + " " + f.title)

private fun placeholder(f: ElicitField): String = when {
    f.format == "date" -> "YYYY-MM-DD"
    f.format == "email" -> "name@example.com"
    f.format == "uri" || f.format == "url" -> "https://…"
    f.kind == "integer" || f.kind == "number" -> listOfNotNull(f.min?.let { "min ${trim(it)}" }, f.max?.let { "max ${trim(it)}" }).joinToString(" · ").ifEmpty { "Number" }
    else -> tr("Type here")
}

private fun trim(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

@Composable
private fun FieldBox(value: String, onChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType, secret: Boolean, error: Boolean) {
    val c = Harness.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceElevated)
            .border(if (error) 1.dp else 0.5.dp, if (error) c.red else c.separator, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, style = Harness.type.body, color = c.tertiaryLabel)
        BasicTextField(
            value, onChange,
            textStyle = Harness.type.body.copy(color = c.label),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UrlElicitationDialog(i: McpInteraction.Url, onOpen: () -> Unit, onDecline: () -> Unit, onCancel: () -> Unit) {
    val c = Harness.colors
    val host = runCatching { java.net.URI(i.url).host }.getOrNull() ?: i.url
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = { Text(trf("{0} wants you to open a page", i.server), style = Harness.type.headline, color = c.label) },
        text = {
            Column {
                if (i.message.isNotBlank()) { Text(i.message, style = Harness.type.body, color = c.label); Spacer(Modifier.size(10.dp)) }
                Text(host, style = Harness.type.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                Text(i.url.take(300), style = Harness.type.caption, color = c.secondaryLabel)
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(tr("Open"), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(tr("Decline"), color = c.secondaryLabel) } },
    )
}

@Composable
private fun SamplingDialog(i: McpInteraction.Sampling, onAnswer: (SamplingDecision) -> Unit) {
    val c = Harness.colors
    AlertDialog(
        onDismissRequest = { onAnswer(SamplingDecision.DENY) },
        containerColor = c.surface,
        title = { Text(trf("{0} wants to use your model", i.server), style = Harness.type.headline, color = c.label) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "It will run this prompt on the model in Settings → Provider, using your API key" +
                        (i.maxTokens?.let { trf(", up to {0} tokens", it) } ?: "") + ".",
                    style = Harness.type.footnote, color = c.secondaryLabel,
                )
                i.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(10.dp))
                    Text("SYSTEM", style = Harness.type.caption, color = c.tertiaryLabel)
                    Text(it.take(1200), style = Harness.type.footnote, color = c.label)
                }
                Spacer(Modifier.size(10.dp))
                Text("PROMPT", style = Harness.type.caption, color = c.tertiaryLabel)
                Text(i.preview, style = Harness.type.footnote, color = c.label)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onAnswer(SamplingDecision.ALWAYS) }) { Text(tr("Always"), color = c.secondaryLabel) }
                TextButton(onClick = { onAnswer(SamplingDecision.ONCE) }) { Text(tr("Allow"), color = c.accent) }
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(SamplingDecision.DENY) }) { Text(tr("Deny"), color = c.red) } },
    )
}
