package dev.harness.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.harness.R

/** Geist (OFL-1.1, Vercel): the FARAI voice. Tight on display sizes, open on body text. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
    Font(R.font.geist_italic, FontWeight.Normal, FontStyle.Italic),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

@Immutable
data class HarnessType(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
    /** Small uppercase-free label for chips and pills (Geist Medium). */
    val label: TextStyle,
    /** Tabular figures for counters, timers and token counts. */
    val numeric: TextStyle,
)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun s(size: Float, weight: FontWeight, line: Float, track: TextUnit, family: FontFamily = Geist, scale: Float) =
    TextStyle(fontFamily = family, fontSize = (size * scale).sp, fontWeight = weight, lineHeight = (line * scale).sp, letterSpacing = track, lineHeightStyle = Trim)

fun harnessType(scale: Float): HarnessType = HarnessType(
    largeTitle = s(34f, FontWeight.SemiBold, 40f, (-0.035).em, scale = scale),
    title1 = s(28f, FontWeight.SemiBold, 34f, (-0.03).em, scale = scale),
    title2 = s(22f, FontWeight.SemiBold, 28f, (-0.022).em, scale = scale),
    title3 = s(19f, FontWeight.SemiBold, 25f, (-0.016).em, scale = scale),
    headline = s(16f, FontWeight.SemiBold, 22f, (-0.01).em, scale = scale),
    body = s(16f, FontWeight.Normal, 24f, (-0.004).em, scale = scale),
    callout = s(15f, FontWeight.Normal, 22f, 0.em, scale = scale),
    subhead = s(14.5f, FontWeight.Normal, 21f, 0.em, scale = scale),
    footnote = s(13f, FontWeight.Normal, 18f, 0.em, scale = scale),
    caption = s(12f, FontWeight.Normal, 16f, 0.01.em, scale = scale),
    mono = s(13f, FontWeight.Normal, 20f, 0.em, GeistMono, scale),
    monoSmall = s(11.5f, FontWeight.Normal, 16f, 0.em, GeistMono, scale),
    label = s(13.5f, FontWeight.Medium, 18f, 0.em, scale = scale),
    numeric = s(12.5f, FontWeight.Medium, 16f, 0.em, GeistMono, scale),
)

fun materialTypography(t: HarnessType) = Typography(
    displaySmall = t.largeTitle,
    headlineLarge = t.title1,
    headlineMedium = t.title2,
    headlineSmall = t.title3,
    titleLarge = t.title3,
    titleMedium = t.headline,
    titleSmall = t.subhead.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = t.body,
    bodyMedium = t.subhead,
    bodySmall = t.footnote,
    labelLarge = t.label,
    labelMedium = t.footnote.copy(fontWeight = FontWeight.Medium),
    labelSmall = t.caption,
)

val LocalHarnessType = staticCompositionLocalOf { harnessType(1f) }
