package dev.harness.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.harness.ui.theme.Harness
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Expressive springs: one family for the whole app so every surface moves the same way. */
object FaraiMotion {
    fun <T> spatial() = spring<T>(dampingRatio = 0.82f, stiffness = 380f)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    fun <T> effects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)
    fun <T> slow() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)
}

/**
 * The FARAI spark path: [points] outer tips alternating with minor tips of radius [minor] (0 to 1
 * of the outer), joined through inner valleys at [inner], smoothed with quadratic curves so the
 * shape reads as soft and organic rather than a clip-art star.
 */
private fun sparkPath(cx: Float, cy: Float, r: Float, minor: Float, inner: Float, points: Int = 4): Path {
    val n = points * 2
    val verts = ArrayList<Offset>(n * 2)
    for (i in 0 until n) {
        val tipR = if (i % 2 == 0) r else r * minor
        val a = (i.toFloat() / n) * 2 * PI - PI / 2
        verts += Offset(cx + (cos(a) * tipR).toFloat(), cy + (sin(a) * tipR).toFloat())
        val va = a + PI / n
        verts += Offset(cx + (cos(va) * r * inner).toFloat(), cy + (sin(va) * r * inner).toFloat())
    }
    val p = Path()
    val m = verts.size
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val start = mid(verts[m - 1], verts[0])
    p.moveTo(start.x, start.y)
    for (i in 0 until m) {
        val cur = verts[i]
        val next = verts[(i + 1) % m]
        val md = mid(cur, next)
        // Tips stay crisp-ish; valleys round off.
        p.quadraticBezierTo(cur.x, cur.y, md.x, md.y)
    }
    p.close()
    return p
}

/** FARAI's signature gradient: ember tip melting into iris. */
@Composable
fun sparkBrushColors(): List<Color> {
    val c = Harness.colors
    return listOf(c.ember, c.pink, c.brandEnd, c.brandStart, c.ember)
}

/**
 * The FARAI spark. Static, it is the brand mark. [live], it breathes: four tips morph into eight
 * and back while the gradient turns, the thinking motion everywhere in the app.
 */
@Composable
fun Spark(size: Dp, modifier: Modifier = Modifier, live: Boolean = false, colors: List<Color> = sparkBrushColors()) {
    val t = rememberInfiniteTransition(label = "spark")
    val morph by if (live) t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "morph")
        else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val spin by if (live) t.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "spin")
        else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val minor = 0.34f + 0.52f * morph
        val inner = 0.30f + 0.12f * morph
        rotate(spin * 0.25f + morph * 22.5f) {
            val path = sparkPath(center.x, center.y, r * 0.98f, minor, inner)
            drawPath(path, Brush.sweepGradient(colors, center))
        }
    }
}

/** Shimmering label: the text drawn dim, with a bright band sweeping through it. */
@Composable
fun ShimmerText(text: String, style: TextStyle, base: Color, highlight: Color, modifier: Modifier = Modifier, active: Boolean = true) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val phase by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sweep")
    Text(
        text,
        style = style,
        color = base,
        maxLines = 1,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                onDrawWithContent {
                    drawContent()
                    if (active) {
                        val center = phase * w
                        val band = max(w * 0.22f, 40f)
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, highlight, Color.Transparent),
                                startX = center - band, endX = center + band,
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    }
                }
            },
    )
}

fun elapsedLabel(secs: Long): String = when {
    secs < 60 -> "${secs}s"
    secs < 3600 -> "${secs / 60}m ${secs % 60}s"
    else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
}

/** Tail-of-turn indicator: live spark, a shimmering verb, and how long it has been going. */
@Composable
fun WorkingIndicator(label: String, sinceMs: Long?, modifier: Modifier = Modifier) {
    val c = Harness.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val secs = sinceMs?.let { max(0L, (now - it) / 1000) } ?: 0L
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Spark(18.dp, live = true)
        ShimmerText(label, Harness.type.label, c.secondaryLabel, c.label)
        if (secs > 0) Text(elapsedLabel(secs), style = Harness.type.numeric, color = c.tertiaryLabel)
    }
}

/** A dot with a ring that keeps expanding and fading: "this is running". */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing)), label = "ring")
    Box(modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 2.2f)) {
            val r = size.toPx() / 2
            drawCircle(color.copy(alpha = (1f - p) * 0.45f), radius = r + (this.size.minDimension / 2 - r) * p)
            drawCircle(color, radius = r)
        }
    }
}

/**
 * FARAI aurora: soft colour fields drifting behind hero areas (welcome, empty chat, the sessions
 * header). Paint only, cheap, and nearly invisible until you look for it.
 */
@Composable
fun Aurora(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val c = Harness.colors
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val k = (if (c.isDark) 0.22f else 0.16f) * intensity
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun blob(x: Float, y: Float, rad: Float, col: Color) =
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = k), Color.Transparent), Offset(x, y), rad), rad, Offset(x, y))
        blob(w * (0.15f + 0.25f * a), h * (0.25f + 0.15f * b), w * 0.75f, c.brandStart)
        blob(w * (0.85f - 0.2f * b), h * (0.2f + 0.2f * a), w * 0.6f, c.brandEnd)
        blob(w * (0.55f + 0.2f * a), h * (0.7f - 0.2f * b), w * 0.55f, c.ember)
    }
}

/** Text appearing as it streams: settled characters solid, the newest ones fading in under a veil. */
@Composable
fun VeilText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, veil: Int = 32) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(text) {
        if (shown.value > text.length) shown.snapTo(text.length.toFloat())
        val behind = text.length - shown.value
        // Catch up proportionally: fast models never leave the reveal far behind.
        val ms = (behind * 9f).coerceIn(60f, 420f).toInt()
        shown.animateTo(text.length.toFloat(), tween(ms, easing = LinearEasing))
    }
    val n = shown.value.toInt().coerceIn(0, text.length)
    val start = (n - veil).coerceAtLeast(0)
    Text(
        buildAnnotatedString {
            append(text.substring(0, start))
            for (i in start until n) {
                val f = (n - i).toFloat() / veil
                withStyle(SpanStyle(color = color.copy(alpha = (1f - f * 0.85f).coerceIn(0.12f, 1f)))) { append(text[i]) }
            }
        },
        style = style,
        color = color,
        modifier = modifier,
    )
}
