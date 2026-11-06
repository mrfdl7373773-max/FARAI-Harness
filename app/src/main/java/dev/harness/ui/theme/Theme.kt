package dev.harness.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.harness.R

object Harness {
    val colors: HarnessColors @Composable @ReadOnlyComposable get() = LocalHarnessColors.current
    val type: HarnessType @Composable @ReadOnlyComposable get() = LocalHarnessType.current
}

@Composable
fun HarnessTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = if (darkTheme) DarkHarness else LightHarness
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val dyn = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(accent = dyn.primary, accentSoft = dyn.primary.copy(alpha = 0.16f))
    } else base
    val type = remember(fontScale) { harnessType(fontScale.coerceIn(0.8f, 1.5f)) }
    val scheme = if (darkTheme) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.indigo,
        background = colors.background, onBackground = colors.label,
        surface = colors.surface, onSurface = colors.label, surfaceVariant = colors.surfaceElevated,
        onSurfaceVariant = colors.secondaryLabel, outline = colors.separator, outlineVariant = colors.separator,
        error = colors.red, surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceElevated,
        surfaceContainerLow = colors.surface, surfaceContainerHighest = colors.surfaceElevated,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, secondary = colors.indigo,
        background = colors.background, onBackground = colors.label,
        surface = colors.surface, onSurface = colors.label, surfaceVariant = colors.surfaceElevated,
        onSurfaceVariant = colors.secondaryLabel, outline = colors.separator, outlineVariant = colors.separator,
        error = colors.red, surfaceContainer = colors.surface, surfaceContainerHigh = colors.surface,
        surfaceContainerLow = colors.surface, surfaceContainerHighest = colors.surfaceElevated,
    )
    CompositionLocalProvider(LocalHarnessColors provides colors, LocalHarnessType provides type) {
        MaterialTheme(
            colorScheme = scheme,
            typography = materialTypography(type),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp),
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
        ) { Backdrop(colors.isDark, content) }
    }
}

/**
 * The FARAI sky (pastel clouds and a rainbow) behind every screen. Screens draw no background
 * of their own; cards and bars are translucent glass over it. Dark mode dims it to dusk.
 */
@Composable
private fun Backdrop(dark: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painterResource(R.drawable.farai_sky), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(if (dark) Color(0xA807070C) else Color(0x14FFFFFF)))
        content()
    }
}
