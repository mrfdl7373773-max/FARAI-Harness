package dev.harness.ui.settings

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.harness.data.settings.ProviderSettings
import dev.harness.ui.components.FieldRow
import dev.harness.ui.components.ListRow
import dev.harness.ui.components.RowDivider
import dev.harness.ui.components.Section
import dev.harness.ui.theme.Harness

/** Editable copy of provider settings; numeric fields are kept as text while typing. */
data class ProviderDraft(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: String,
    val maxTokens: String,
    val maxTurns: String,
    val timeout: String,
    val contextWindow: String,
) {
    fun apply(p: ProviderSettings): ProviderSettings = p.copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: p.temperature,
        maxTokens = maxTokens.toIntOrNull()?.let { if (it <= 0) 0 else it.coerceIn(256, 1_000_000) } ?: p.maxTokens,
        maxTurns = maxTurns.toIntOrNull()?.coerceIn(1, 200) ?: p.maxTurns,
        timeoutSeconds = timeout.toIntOrNull()?.coerceIn(5, 900) ?: p.timeoutSeconds,
        contextWindow = contextWindow.toIntOrNull()?.coerceIn(1_000, 10_000_000) ?: p.contextWindow,
    )

    companion object {
        fun of(p: ProviderSettings) = ProviderDraft(
            p.baseUrl, p.apiKey, p.model, p.temperature.toString(), p.maxTokens.toString(),
            p.maxTurns.toString(), p.timeoutSeconds.toString(), p.contextWindow.toString(),
        )
    }
}

@Composable
fun ProviderForm(
    draft: ProviderDraft,
    onChange: (ProviderDraft) -> Unit,
    connection: ConnectionState,
    onTest: () -> Unit,
    onPickModel: (() -> Unit)?,
    showAdvanced: Boolean = true,
) {
    val c = Harness.colors
    var reveal by remember { mutableStateOf(false) }
    Column {
        Section(header = "Endpoint", footer = tr("Any OpenAI-compatible API: OpenAI, OpenRouter, Groq, Together, a local server…")) {
            FieldRow("Base URL", draft.baseUrl, { onChange(draft.copy(baseUrl = it)) }, "https://…/v1", KeyboardType.Uri)
            RowDivider(16.dp)
            FieldRow("API Key", draft.apiKey, { onChange(draft.copy(apiKey = it)) }, "sk-…", KeyboardType.Password, secret = true, revealed = reveal) {
                IconButton(onClick = { reveal = !reveal }, modifier = Modifier.size(32.dp)) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, tr("Reveal"), tint = c.secondaryLabel)
                }
            }
            RowDivider(16.dp)
            FieldRow("Model", draft.model, { onChange(draft.copy(model = it)) }, "model-id")
            if (onPickModel != null) {
                RowDivider(16.dp)
                ListRow(tr("Choose from provider…"), titleColor = c.accent, chevron = true, onClick = onPickModel)
            }
        }
        Section(footer = when (connection) {
            is ConnectionState.Ok -> trf("Connected in {0} ms · {1} models", connection.latencyMs, connection.models) +
                if (!connection.modelFound) tr(" · model not listed (it may still work)") else ""
            is ConnectionState.Failed -> connection.message
            else -> tr("Calls GET /models with these settings. Save first to test new values.")
        }) {
            ListRow(
                title = tr("Test Connection"),
                titleColor = c.accent,
                onClick = onTest,
                trailing = {
                    when (connection) {
                        ConnectionState.Testing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        is ConnectionState.Ok -> Text("✓", style = Harness.type.headline, color = c.green)
                        is ConnectionState.Failed -> Text("✕", style = Harness.type.headline, color = c.red)
                        ConnectionState.Idle -> Unit
                    }
                },
            )
        }
        if (showAdvanced) {
            Section(header = "Generation") {
                FieldRow("Temperature", draft.temperature, { onChange(draft.copy(temperature = it)) }, "0.2", KeyboardType.Decimal)
                RowDivider(16.dp)
                FieldRow(tr("Max tokens (0 = provider default)"), draft.maxTokens, { onChange(draft.copy(maxTokens = it)) }, "16384", KeyboardType.Number)
                RowDivider(16.dp)
                FieldRow("Max turns", draft.maxTurns, { onChange(draft.copy(maxTurns = it)) }, "30", KeyboardType.Number)
                RowDivider(16.dp)
                FieldRow(tr("Timeout (s)"), draft.timeout, { onChange(draft.copy(timeout = it)) }, "120", KeyboardType.Number)
                RowDivider(16.dp)
                FieldRow("Context", draft.contextWindow, { onChange(draft.copy(contextWindow = it)) }, "128000", KeyboardType.Number)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Context is the model's window in tokens; compaction triggers at the threshold set under Agent.",
                style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
