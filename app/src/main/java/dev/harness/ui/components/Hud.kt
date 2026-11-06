package dev.harness.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.delay

/** Floating capsule message, like an iOS HUD. Call inside a Box. */
@Composable
fun BoxScope.Hud(message: String?, onShown: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            text = message; visible = true; onShown()
            delay(2200); visible = false
        }
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp, start = 24.dp, end = 24.dp),
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        Box(Modifier.shadow(18.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Harness.colors.surfaceElevated).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(text, style = Harness.type.subhead, color = Harness.colors.label)
        }
    }
}
