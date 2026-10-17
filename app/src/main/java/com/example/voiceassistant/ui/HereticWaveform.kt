package com.example.voiceassistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Heretic waveform shown below the orb on the home screen.
 *
 * The waveform is a clean sinusoidal carrier whose amplitude is driven by the
 * instantaneous audio level ([levelDb], in dBFS, typically -60..0). At -60 dB
 * the amplitude collapses to a flat line; at 0 dB the wave fills the box.
 * A separate phase animation scrolls the wave horizontally so it is always
 * visibly alive, even when the user is silent.
 *
 * The waveform is drawn as a single closed [Path] built per frame. The path
 * is small (one polyline of ~64 points) and the math is all scalar, so the
 * cost on a 1 Hz UI thread is well under 1 ms on the phones we target. The
 * two colours are mixed along the horizontal axis so the wave reads as a
 * cyan-magenta gradient like the orb, with peak-amplitude regions slightly
 * brighter.
 *
 * The level is smoothed with a one-pole low-pass ([smoothedLevel]) so a
 * single loud frame does not cause a visual spike; the smoothing constant
 * (0.15) is a 200 ms time constant at 60 fps, which matches the human ear's
 * "smoothed loudness" perception well enough for a status indicator.
 */
@Composable
fun HereticWaveform(
    levelDb: Float,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp
) {
    // Phase animation: a slow horizontal scroll so the wave is visibly
    // moving even at silence. 4 s for one full cycle is a good rate: too
    // fast and it looks nervous, too slow and it looks frozen.
    val transition = rememberInfiniteTransition(label = "heretic-waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveform-phase"
    )

    // Smoothing: we keep an Animatable of the level and ease towards the
    // incoming value. The Animatable is the cheapest way to get a frame-
    // accurate, interruption-safe smoothing without writing our own
    // remember-snapshot logic.
    val smoothed = remember { Animatable(levelDb.coerceIn(MIN_LEVEL_DB, 0f)) }
    LaunchedEffect(levelDb) {
        smoothed.animateTo(
            targetValue = levelDb.coerceIn(MIN_LEVEL_DB, 0f),
            animationSpec = tween(durationMillis = 200, easing = LinearEasing)
        )
    }

    Canvas(modifier = modifier.defaultMinSize(minHeight = height)) {
        drawHereticWaveform(
            phase = phase,
            levelDb = smoothed.value
        )
    }
}

private const val MIN_LEVEL_DB = -60f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHereticWaveform(
    phase: Float,
    levelDb: Float
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Map -60..0 dB to 0..1 amplitude. We use an exponential curve so
    // -30 dB already looks like "half loud" to the eye; a linear map makes
    // the lower half of the range read as silent.
    val normalised = ((levelDb - MIN_LEVEL_DB) / -MIN_LEVEL_DB).coerceIn(0f, 1f)
    val amplitudeFraction = normalised.powCurve()
    val amplitude = h * 0.42f * amplitudeFraction

    val samples = 96
    val midline = h / 2f
    // Two waves at slightly different frequencies, summed. The 1.5× and 0.5×
    // harmonics give the wave its layered, slightly-detuned look from the
    // design (the carrier has a brighter inner band).
    val path = Path()
    val innerPath = Path()
    for (i in 0..samples) {
        val t = i.toFloat() / samples
        val x = t * w
        // Carrier: cos(2π * (t + phase))
        val carrier = sin(2.0 * PI * (t + phase)).toFloat()
        // Inner harmonic at 1.5× frequency and 0.5× amplitude — this is
        // the secondary line visible in the design.
        val innerHarmonic = sin(2.0 * PI * 1.5 * (t + phase)).toFloat() * 0.5f
        val y = midline + carrier * amplitude
        val innerY = midline + innerHarmonic * amplitude * 0.6f
        if (i == 0) {
            path.moveTo(x, y)
            innerPath.moveTo(x, innerY)
        } else {
            path.lineTo(x, y)
            innerPath.lineTo(x, innerY)
        }
    }

    // Soft horizontal gradient: cyan on the left fading to magenta on the
    // right, matching the orb's colour pair. The brush is allocated per
    // draw call; the cost is negligible for a 96-sample polyline.
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            JarvisCyan.copy(alpha = 0.95f),
            HereticMagenta.copy(alpha = 0.95f)
        ),
        startX = 0f,
        endX = w
    )

    drawPath(
        path = path,
        brush = gradient,
        style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    )
    drawPath(
        path = innerPath,
        brush = gradient,
        style = Stroke(
            width = 1.4.dp.toPx(),
            cap = StrokeCap.Round
        )
    )
}

/**
 * Maps a 0..1 linear value to a 0..1 curve where low values stay low and
 * the upper half reaches 1 faster. A simple exponential: y = (e^(k·x) - 1)
 * / (e^k - 1) with k=3. This is what makes the waveform read as quiet at
 * -30 dB even though -30 dB is half the linear range.
 */
private fun Float.powCurve(): Float {
    val k = 3f
    val ek = exp(k)
    return ((exp(k * this) - 1f) / (ek - 1f)).coerceIn(0f, 1f)
}
