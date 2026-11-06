package dev.harness.ui.onboarding

import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.harness.ui.components.MarkdownBlock
import dev.harness.ui.components.PillButton
import dev.harness.ui.settings.SettingsViewModel
import dev.harness.ui.theme.Harness

/** First-run screen: renders prompts/onboarding.md, then leads to provider setup. */
@Composable
fun OnboardingScreen(vm: SettingsViewModel, onConfigure: () -> Unit) {
    val c = Harness.colors
    val text = remember { vm.onboardingText() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize()) {
        dev.harness.ui.components.Aurora(Modifier.fillMaxWidth().height(480.dp), intensity = 1.8f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(48.dp))
                AnimatedVisibility(shown, enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 4 }) {
                    Column {
                        dev.harness.ui.components.FaraiLogo(84.dp)
                        Spacer(Modifier.height(22.dp))
                        Text("FARAI", style = Harness.type.largeTitle.copy(fontSize = Harness.type.largeTitle.fontSize * 1.4f), color = c.label)
                        Text(tr("A coding agent that lives on your phone."), style = Harness.type.title3.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.secondaryLabel)
                    }
                }
                Spacer(Modifier.height(28.dp))
                AnimatedVisibility(shown, enter = fadeIn(tween(700, delayMillis = 150))) {
                    MarkdownBlock(text, c.label)
                }
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                PillButton(tr("Connect a model"), onClick = onConfigure)
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can change everything later in Settings.",
                    style = Harness.type.footnote, color = c.secondaryLabel,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
