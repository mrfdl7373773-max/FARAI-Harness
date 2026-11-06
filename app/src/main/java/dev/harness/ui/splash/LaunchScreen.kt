package dev.harness.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.harness.ui.components.FoxMark
import dev.harness.ui.components.FoxPixels
import dev.harness.ui.theme.Geist
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Night = Color(0xFF07080A)

/**
 * Launch sequence: plain white, the fox rises in, "FARAI by Farrel" settles under it; then the
 * mark gives way to a mint spinner while the app finishes loading ([ready]); [onDone] when both
 * the minimum show time and loading are through.
 */
@Composable
fun LaunchScreen(ready: Boolean, dark: Boolean, onDone: () -> Unit) {
    // White in light mode, black in dark mode (follows the app's theme setting).
    val bg = if (dark) Night else Color.White
    val ink = if (dark) Color(0xFFF2F3F5) else Color(0xFF0E1116)
    val quiet = if (dark) Color(0xFF7D828B) else Color(0xFF8A8F98)
    val fox = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val brand = remember { Animatable(1f) }
    val spinner = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    val readyNow by rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        launch { fox.animateTo(1f, tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        delay(380)
        words.animateTo(1f, tween(650))
        delay(950)
        // Welcome done: brand fades, spinner takes over until the app is ready.
        launch { brand.animateTo(0f, tween(320)) }
        delay(160)
        spinner.animateTo(1f, tween(260))
        delay(700) // at least one full turn, so it reads as loading and not a flicker
        while (!readyNow) delay(50)
        out.animateTo(0f, tween(320))
        onDone()
    }

    Box(Modifier.fillMaxSize().alpha(out.value).background(bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.alpha(brand.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoxMark(
                112.dp,
                eyes = bg,
                modifier = Modifier.alpha(fox.value).scale(0.88f + 0.12f * fox.value).offset(y = (14 * (1 - fox.value)).dp),
            )
            Spacer(Modifier.height(44.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold, letterSpacing = 0.32.em)) { append("FARAI") }
                    withStyle(SpanStyle(color = quiet, fontWeight = FontWeight.Normal, letterSpacing = 0.06.em)) { append("  by Farrel") }
                },
                style = TextStyle(fontFamily = Geist, fontSize = 15.sp),
                modifier = Modifier.alpha(words.value).offset(y = (8 * (1 - words.value)).dp),
            )
        }
        if (spinner.value > 0f) Spinner(dark, Modifier.size(34.dp).alpha(spinner.value).scale(0.8f + 0.2f * spinner.value))
    }
}

/** A mint arc that sweeps round and breathes in length. */
@Composable
private fun Spinner(dark: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    val sweep by t.animateFloat(40f, 250f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "sweep")
    Canvas(modifier.rotate(angle)) {
        val w = size.minDimension * 0.11f
        val inset = w / 2
        drawArc(if (dark) Color(0xFF14231E) else Color(0xFFE9F7F2), 0f, 360f, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w))
        drawArc(
            Brush.sweepGradient(listOf(FoxPixels.shade, FoxPixels.body, FoxPixels.shade)),
            -90f, sweep, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}
