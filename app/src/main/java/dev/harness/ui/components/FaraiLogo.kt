package dev.harness.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.harness.R

/**
 * The FARAI Harness app icon, drawn from the same adaptive-icon layers as the launcher icon.
 * Adaptive layers are 108dp with a 72dp visible area, so they are drawn 1.5x and clipped.
 */
@Composable
fun FaraiLogo(size: Dp, modifier: Modifier = Modifier) {
    // Just the fox, no tile: transparent over the sky (Farrel, 2026-11-06).
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        FoxMark(size * 0.9f)
    }
}
