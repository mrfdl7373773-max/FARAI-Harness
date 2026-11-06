package dev.harness.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * FARAI palette: cool graphite neutrals with FARAI's own voice: an Iris accent
 * for actions and an Ember spark for anything alive (thinking, writing, running).
 */
@Immutable
data class HarnessColors(
    val isDark: Boolean,
    /** Page. */
    val background: Color,
    /** Cards, grouped rows, the composer. */
    val surface: Color,
    /** Chips, inputs, the user's message, pressed rows. */
    val surfaceElevated: Color,
    /** Header and sheet tone (same as the page: chrome stays quiet). */
    val bar: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val accent: Color,
    val accentSoft: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val indigo: Color,
    val purple: Color,
    val teal: Color,
    val pink: Color,
    val gray: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val agentBubble: Color,
    val agentBubbleText: Color,
    val codeBackground: Color,
    val brandStart: Color,
    val brandEnd: Color,
    /** The FARAI spark: live work. */
    val ember: Color,
    val codeBorder: Color,
    val diffAdd: Color,
    val diffDel: Color,
    /** Hairline for tool rails and quiet borders. */
    val rail: Color,
    val onAccent: Color = Color.White,
    /** Answer cards: near-solid so long text reads cleanly over the sky. */
    val card: Color = Color(0xF5FFFFFF),
    /** Floating chrome (header pill, composer, round buttons): frosted glass. */
    val glass: Color = Color(0xD9FFFFFF),
    val glassBorder: Color = Color(0x99FFFFFF),
)

val LightHarness = HarnessColors(
    isDark = false,
    background = Color(0xFFF4F4F6),
    surface = Color(0xD9FFFFFF), // glass over the sky backdrop
    surfaceElevated = Color(0xC7F1F0F6),
    bar = Color(0xB8F6F4FA),
    label = Color(0xFF1D1D23),
    secondaryLabel = Color(0xFF62626B),
    tertiaryLabel = Color(0xFF9A9AA3),
    separator = Color(0xFFE3E3E8),
    fill = Color(0xFFE6E6EB),
    accent = Color(0xFF5144EE),
    accentSoft = Color(0x1F5144EE),
    green = Color(0xFF15803D),
    red = Color(0xFFDC2626),
    orange = Color(0xFFB45309),
    indigo = Color(0xFF4F46E5),
    purple = Color(0xFF7E22CE),
    teal = Color(0xFF0F766E),
    pink = Color(0xFFBE185D),
    gray = Color(0xFF71717A),
    userBubble = Color(0x8CFFFFFF),
    userBubbleText = Color(0xFF1D1D23),
    agentBubble = Color(0x00000000),
    agentBubbleText = Color(0xFF1D1D23),
    codeBackground = Color(0xFFFAFAFB),
    brandStart = Color(0xFF4F46E5),
    brandEnd = Color(0xFFA855F7),
    ember = Color(0xFFF2602F),
    codeBorder = Color(0xFFE6E6EA),
    diffAdd = Color(0xFF15803D),
    diffDel = Color(0xFFDC2626),
    rail = Color(0x29000000),
)

val DarkHarness = HarnessColors(
    isDark = true,
    background = Color(0xFF07070A),
    surface = Color(0xD1121218), // glass over the dimmed sky backdrop
    surfaceElevated = Color(0xD91B1B22),
    bar = Color(0xB307070A),
    label = Color(0xFFECECEF),
    secondaryLabel = Color(0xFFA6A6AF),
    tertiaryLabel = Color(0xFF6B6B74),
    separator = Color(0xFF212127),
    fill = Color(0xFF1F1F25),
    accent = Color(0xFF8D85FF),
    accentSoft = Color(0x2E8D85FF),
    green = Color(0xFF34D399),
    red = Color(0xFFF87171),
    orange = Color(0xFFFBBF24),
    indigo = Color(0xFF818CF8),
    purple = Color(0xFFC084FC),
    teal = Color(0xFF5EEAD4),
    pink = Color(0xFFF472B6),
    gray = Color(0xFF8E8E98),
    userBubble = Color(0x33FFFFFF),
    userBubbleText = Color(0xFFECECEF),
    card = Color(0xEB15151B),
    glass = Color(0xC71A1A21),
    glassBorder = Color(0x1FFFFFFF),
    agentBubble = Color(0x00000000),
    agentBubbleText = Color(0xFFECECEF),
    codeBackground = Color(0xFF0C0C0F),
    brandStart = Color(0xFF6366F1),
    brandEnd = Color(0xFFB37BFF),
    ember = Color(0xFFFF7A45),
    codeBorder = Color(0xFF1F1F25),
    diffAdd = Color(0xFF34D399),
    diffDel = Color(0xFFF87171),
    rail = Color(0x1FFFFFFF),
)

val LocalHarnessColors = staticCompositionLocalOf { DarkHarness }
