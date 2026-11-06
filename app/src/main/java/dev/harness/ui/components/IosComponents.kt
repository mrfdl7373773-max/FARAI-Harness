package dev.harness.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import dev.harness.ui.i18n.tr
import dev.harness.ui.i18n.trf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.harness.ui.theme.Harness

// FARAI component kit. The names are the ones every screen already uses; the look is new:
// FARAI's quiet graphite grammar: tonal circles, connected pills, segmented cards.

/** The app-wide press spring. */
val SoftSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 520f)

/** Presses sink a little and spring back, everywhere. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.95f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, SoftSpring, label = "press")
    return this.scale(s)
}

/** Round tonal button: back, close, more, and every header action. */
@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Harness.colors.label,
    container: Color = if (LocalGlassButtons.current) Harness.colors.glass else Harness.colors.surfaceElevated,
    size: Dp = 42.dp,
    enabled: Boolean = true,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Box(
            Modifier.size(size).pressScale(interaction, 0.88f).clip(CircleShape).background(container)
                .clickable(interaction, null, enabled = enabled) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription, tint = if (enabled) tint else Harness.colors.tertiaryLabel, modifier = Modifier.size(size * 0.5f)) }
        if (badge != null && badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(18.dp).clip(CircleShape).background(Harness.colors.accent),
                contentAlignment = Alignment.Center,
            ) { Text("$badge", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
        }
    }
}

/** Screen header: tonal back circle, centred title and subtitle, tonal action circles. */
@Composable
fun NavBar(
    title: String,
    modifier: Modifier = Modifier,
    collapsed: Boolean = true,
    subtitle: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // No bar: everything floats over the sky. The title sits in a frosted pill between
    // frosted round buttons, so content can scroll underneath like a glass header.
    val c = Harness.colors
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
        // Back | title | actions. The title is centred on the screen when there is room and
        // otherwise shifts to sit between the two sides, so it never runs under a button.
        androidx.compose.ui.layout.Layout(
            contents = listOf(
                {
                    if (onBack != null) CompositionLocalProvider(LocalGlassButtons provides true) {
                        CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, backLabel ?: "Back", onClick = onBack)
                    }
                },
                {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedVisibility(visible = collapsed, enter = fadeIn() + scaleIn(initialScale = 0.96f), exit = fadeOut() + scaleOut(targetScale = 0.96f)) {
                            Column(
                                Modifier.clip(CircleShape).background(c.glass).border(1.dp, c.glassBorder, CircleShape)
                                    .padding(horizontal = 18.dp, vertical = if (subtitle != null) 7.dp else 11.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(title, style = Harness.type.headline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (subtitle != null) Text(subtitle, style = Harness.type.caption, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        titleAccessory?.invoke()
                    }
                },
                {
                    CompositionLocalProvider(LocalGlassButtons provides true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                    }
                },
            ),
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
        ) { (startM, titleM, endM), constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val start = startM.firstOrNull()?.measure(loose)
            val end = endM.firstOrNull()?.measure(loose)
            val gap = 8.dp.roundToPx()
            val sw = start?.width ?: 0
            val ew = end?.width ?: 0
            val startEdge = if (sw > 0) sw + gap else 0
            val endEdge = if (ew > 0) ew + gap else 0
            val room = (w - startEdge - endEdge).coerceAtLeast(0)
            val title = titleM.firstOrNull()?.measure(loose.copy(maxWidth = room))
            layout(w, h) {
                start?.placeRelative(0, (h - start.height) / 2)
                end?.placeRelative(w - ew, (h - end.height) / 2)
                if (title != null) {
                    val centred = (w - title.width) / 2
                    val x = centred.coerceIn(startEdge, (w - endEdge - title.width).coerceAtLeast(startEdge))
                    title.placeRelative(x, (h - title.height) / 2)
                }
            }
        }
    }
}

/** Inside a [NavBar], round buttons default to frosted glass instead of a tonal fill. */
val LocalGlassButtons = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
        Text(text, style = Harness.type.largeTitle, color = Harness.colors.label)
        if (subtitle != null) Text(subtitle, style = Harness.type.subhead, color = Harness.colors.secondaryLabel)
    }
}

/** Header action: a tonal circle. */
@Composable
fun BarIcon(icon: ImageVector, contentDescription: String, tint: Color = Harness.colors.label, enabled: Boolean = true, onClick: () -> Unit) {
    CircleButton(icon, contentDescription, tint = tint, enabled = enabled, onClick = onClick)
}

@Composable
fun BarTextButton(text: String, bold: Boolean = false, enabled: Boolean = true, color: Color = Harness.colors.accent, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(interaction).clip(CircleShape)
            .background(if (bold && enabled) color else Harness.colors.surfaceElevated)
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = Harness.type.label.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium),
            color = when { !enabled -> Harness.colors.tertiaryLabel; bold -> Color.White; else -> color },
        )
    }
}

/**
 * A grouped section: an accent header in sentence case, then a card whose rows are separated by
 * thin gaps of page colour (a segmented list), not hairlines.
 */
@Composable
fun Section(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Harness.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(
                header.lowercase().replaceFirstChar { it.uppercase() },
                style = Harness.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = c.accent,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp, top = 24.dp),
            )
        } else Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface), content = content)
        if (footer != null) {
            Text(footer, style = Harness.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
        }
    }
}

/** The gap between two rows of a section. */
@Composable
fun RowDivider(@Suppress("UNUSED_PARAMETER") inset: Dp = 58.dp) {
    Box(Modifier.fillMaxWidth().height(2.dp).background(Harness.colors.background))
}

/** Tonal circle holding a row's icon; [color] tints the glyph only, keeping rows calm. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 34.dp) {
    val c = Harness.colors
    Box(Modifier.size(size).clip(CircleShape).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (color == c.accent || color == c.label) c.label else color, modifier = Modifier.size(size * 0.52f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = Harness.colors.label,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color = Harness.colors.label,
    chevron: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Harness.colors
    val click = if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier
    Row(
        modifier.fillMaxWidth().heightIn(min = 58.dp).then(click).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconTile(icon, iconColor); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Harness.type.body.copy(fontWeight = FontWeight.Medium), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Harness.type.footnote, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(value, style = Harness.type.subhead, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp))
        }
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.tertiaryLabel, modifier = Modifier.padding(start = 4.dp).size(22.dp))
    }
}

/**
 * Material-3-expressive switch in FARAI colours: the thumb grows and gains a check when on,
 * and travels on a spring.
 */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Harness.colors
    val haptics = LocalHapticFeedback.current
    val track by animateColorAsState(if (checked) c.accent else c.fill, label = "track")
    val border by animateColorAsState(if (checked) c.accent else c.tertiaryLabel, label = "border")
    val offset by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "thumb")
    val thumb by animateDpAsState(if (checked) 24.dp else 16.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "thumbSize")
    Box(
        Modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape).background(track).border(2.dp, border, CircleShape)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = offset + (24.dp - thumb) / 2).size(thumb).clip(CircleShape)
                .background(if (checked) Color.White else c.tertiaryLabel),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(checked, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector? = null, iconColor: Color = Harness.colors.label, subtitle: String? = null) {
    ListRow(title = title, icon = icon, iconColor = iconColor, subtitle = subtitle, onClick = { onCheckedChange(!checked) }, trailing = {
        Spacer(Modifier.width(10.dp))
        IosSwitch(checked, onCheckedChange)
    })
}

/** Connected pill group: the chosen option is a filled accent pill sliding on a spring. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Harness.colors
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(c.surfaceElevated).padding(4.dp)) {
        val w = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.72f, stiffness = 420f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(c.accent))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val fg by animateColorAsState(if (i == selected) Color.White else c.label, label = "segText")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) {
                        if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(i)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Harness.type.label.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1)
                }
            }
        }
    }
}

/** Filter pill: filled accent when on, tonal otherwise, with an optional count bubble. */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, count: Int? = null, icon: ImageVector? = null) {
    val c = Harness.colors
    val bg by animateColorAsState(if (selected) c.accent else c.surfaceElevated, FaraiMotion.effects(), label = "pill")
    val fg by animateColorAsState(if (selected) Color.White else c.label, FaraiMotion.effects(), label = "pillFg")
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.heightIn(min = 38.dp).pressScale(interaction).clip(CircleShape).background(bg)
            .clickable(interaction, null, onClick = onClick).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, style = Harness.type.label, color = fg)
        if (count != null && count > 0) {
            Box(Modifier.clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.22f) else c.accentSoft).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Text("$count", style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (selected) Color.White else c.accent)
            }
        }
    }
}

/** Primary action: a full-width accent capsule. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, color: Color = Harness.colors.accent, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (enabled) color else Harness.colors.fill, label = "pillBtn")
    Box(
        modifier.fillMaxWidth().height(54.dp).pressScale(interaction, 0.97f).clip(CircleShape).background(bg)
            .clickable(interaction, null, enabled = enabled && !loading) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(text, style = Harness.type.headline, color = if (enabled) Color.White else Harness.colors.tertiaryLabel)
    }
}

/** Form row: label above-left, value inline; quiet until focused. */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    revealed: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Harness.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Harness.type.caption.copy(fontWeight = FontWeight.Medium), color = c.secondaryLabel)
            Spacer(Modifier.height(3.dp))
            Box {
                if (value.isEmpty()) Text(placeholder, style = Harness.type.body, color = c.tertiaryLabel, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = Harness.type.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrect = false),
                    visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        trailing?.invoke()
    }
}

/** Multi-line monospace area inside a section. */
@Composable
fun TextAreaRow(value: String, onValueChange: (String) -> Unit, placeholder: String, minLines: Int = 4) {
    val c = Harness.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Harness.type.mono, color = c.tertiaryLabel)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = Harness.type.mono.copy(color = c.label),
            cursorBrush = SolidColor(c.accent),
            minLines = minLines,
            keyboardOptions = KeyboardOptions(autoCorrect = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Search capsule with a clear button. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Harness.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp).clip(CircleShape).background(c.surfaceElevated).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Icon(Icons.Rounded.Search, null, tint = c.secondaryLabel, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Harness.type.body, color = c.secondaryLabel, maxLines = 1)
            BasicTextField(value, onValueChange, singleLine = true, textStyle = Harness.type.body.copy(color = c.label), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        AnimatedVisibility(value.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            CircleButton(Icons.Rounded.Close, tr("Clear"), size = 32.dp, container = c.fill, tint = c.secondaryLabel) { onValueChange("") }
        }
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
    }
}

/** Status text with a small leading mark ("✓ Done", "• Input"). */
@Composable
fun Capsule(text: String, color: Color = Harness.colors.accent, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(CircleShape).background(if (filled) color else color.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, style = Harness.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (filled) Color.White else color, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector?,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Harness.colors
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (leading != null) leading() else if (icon != null) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.secondaryLabel, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = Harness.type.title2, color = c.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = Harness.type.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center)
    }
}

/** A soft drop shadow tuned for floating chrome (composer, toolbars). */
fun Modifier.floating(shape: androidx.compose.ui.graphics.Shape, dark: Boolean): Modifier =
    this.shadow(if (dark) 0.dp else 18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))

val ScreenBottomPadding = PaddingValues(bottom = 48.dp)
