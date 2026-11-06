package dev.harness.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

internal object LongPressLatch { var claimed: androidx.compose.ui.input.pointer.PointerId? = null }

/**
 * A long-press that always fires, even over links, code blocks and other clickable children:
 * it watches touches before the children do (Initial pass), so nothing inside can swallow it.
 * Fires after [delayMs] (shorter than the system's) if the finger stays roughly still; once it
 * fires, the rest of that touch is consumed so the child does not also treat it as a tap.
 */
fun Modifier.easyLongPress(delayMs: Long = 320L, onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier = pointerInput(onLongPress) {
    val slop = viewConfiguration.touchSlop * 1.5f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        val released = withTimeoutOrNull(delayMs) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!ch.pressed) return@withTimeoutOrNull true
                if ((ch.position - start).getDistance() > slop) return@withTimeoutOrNull true
                if (ev.changes.size > 1) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") true
        }
        if (released == null) {
            // Nested long-press areas (a code block inside an answer): the inner one fires a bit
            // earlier and claims this touch, so the outer one stays quiet.
            if (LongPressLatch.claimed != down.id) {
                LongPressLatch.claimed = down.id
                onLongPress(start)
            }
            // Swallow the rest of this touch so children don't also see a click.
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                if (ev.changes.none { it.pressed }) break
            }
        }
    }
}



/**
 * A small menu that opens where the finger is: centred on the press, just above it (so the
 * finger doesn't hide it), or just below when there's no room above. [press] is in the
 * coordinates of the composable this is placed in (a Box filling the pressed element).
 */
@androidx.compose.runtime.Composable
fun FingerMenu(
    expanded: Boolean,
    press: androidx.compose.ui.geometry.Offset,
    onDismiss: () -> Unit,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.roundToPx() }
    val provider = androidx.compose.runtime.remember(press, gap) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val fx = anchorBounds.left + press.x.toInt()
                val fy = anchorBounds.top + press.y.toInt()
                val x = (fx - popupContentSize.width / 2).coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val above = fy - popupContentSize.height - gap * 3
                val y = if (above >= gap * 4) above else (fy + gap * 2).coerceAtMost(windowSize.height - popupContentSize.height - gap)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    val c = dev.harness.ui.theme.Harness.colors
    androidx.compose.ui.window.Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
        androidx.compose.animation.AnimatedVisibility(
            shown,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(160), initialScale = 0.85f),
        ) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier
                    .shadow(16.dp, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(c.surfaceElevated)
                    .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                    .padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}
