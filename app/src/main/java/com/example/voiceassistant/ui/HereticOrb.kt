package com.example.voiceassistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.statemachine.AssistantUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 3 s deliberate hold opens the Diagnose tab. Compose's default
 * long-press threshold is ~400 ms, which made the Diagnose tab
 * open whenever the user held the orb for a slow tap. 3 s matches
 * the user's mental model: a press is for listening, a sustained
 * hold is for diagnostics.
 */
private const val ORB_LONG_PRESS_THRESHOLD_MS = 3_000L

/**
 * The Heretic orb shown on the home screen.
 *
 * The design is a multi-ring sphere with a magenta plasma core, a diamond sigil
 * in the middle, and a faint "HERETIC" mark inside the inner ring. The rings
 * rotate slowly in alternating directions; the whole orb pulses when the
 * assistant is actively listening or processing.
 *
 * The orb is drawn into a single [Canvas]. The two path objects ([diamond]
 * and [cross]) are built once via [remember] so per-frame allocation is just
 * a [translate] copy on each path — every other draw call re-uses geometry.
 * State changes that affect the rotation (Listening, Processing) bump the
 * [Animatable] for the pulse so the same set of rings reads as alive.
 *
 * `combinedClickable` keeps the long-press → diagnose gesture so the existing
 * `AssistantOrb` test tag and the long-press hook into the diagnose screen
 * still work. The hit area is the full orb box, not just the visible rings,
 * so a user who taps the dark space between rings still gets a press
 * response.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HereticOrb(
    uiState: AssistantUiState,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    size: Dp = 168.dp,
    modifier: Modifier = Modifier
) {
    // Two rotations in opposite directions for the cyan and magenta ring
    // layers, plus a slower rotation for the dashed outer ring. They are all
    // driven by a single [rememberInfiniteTransition] so the frame timing is
    // synchronised and there is exactly one animation tick per frame, not
    // three.
    val transition = rememberInfiniteTransition(label = "heretic-orb")
    val ringRotationCyan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring-rotation-cyan"
    )
    val ringRotationMagenta by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring-rotation-magenta"
    )
    val ringRotationOuter by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring-rotation-outer"
    )

    // Pulse animates between 1.0 and 1.06. Listening/Processing drives the
    // upper bound higher; idle lets it sit at 1.0. The animation is one-way:
    // a state change updates the [Animatable] target, and tween() smooths the
    // transition.
    val pulse = remember { Animatable(1f) }
    val isActive = uiState is AssistantUiState.Listening ||
        uiState is AssistantUiState.Processing
    LaunchedEffect(isActive) {
        val target = if (isActive) 1.06f else 1.0f
        pulse.animateTo(target, animationSpec = tween(600, easing = FastOutSlowInEasing))
    }
    val pulseValue = pulse.value

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            // Custom press detection so the long-press threshold is
            // explicit. Compose's [combinedClickable] uses the platform
            // default long-press timeout (~400 ms), which is too short:
            // a user who taps the orb to start listening would
            // sometimes hold for half a second and accidentally open
            // the Diagnose tab. We want a deliberate, sustained
            // gesture to open Diagnose, so the threshold is 3 s.
            //
            // Behavior:
            //   * press + release before 3 s   -> onClick()  (start/stop listening)
            //   * press held >= 3 s            -> onLongPress() (open Diagnose)
            //   * long-press fires once; the eventual release does nothing
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var longPressFired = false
                        val longPressJob = launch {
                            delay(ORB_LONG_PRESS_THRESHOLD_MS)
                            longPressFired = true
                            down.consume()
                            onLongPress()
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.changedToUp()) {
                                longPressJob.cancel()
                                if (!longPressFired) onClick()
                                break
                            }
                            if (!change.pressed) {
                                longPressJob.cancel()
                                break
                            }
                        }
                    }
                }
            }
            .testTag("AssistantOrb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            drawHereticOrb(
                ringRotationCyan = ringRotationCyan,
                ringRotationMagenta = ringRotationMagenta,
                ringRotationOuter = ringRotationOuter,
                pulse = pulseValue,
                isActive = isActive
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHereticOrb(
    ringRotationCyan: Float,
    ringRotationMagenta: Float,
    ringRotationOuter: Float,
    pulse: Float,
    isActive: Boolean
) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    val baseRadius = min(w, h) / 2f
    val r = baseRadius * pulse
    // A small inset so the outermost ring sits just inside the box edge.
    val outerR = r * 0.96f
    val midR = r * 0.78f
    val innerR = r * 0.58f
    val coreR = r * 0.36f
    val sigilR = r * 0.18f

    // Outer dashed ring (slow rotation, low alpha). The dash pattern is fixed
    // in pixel space; the dashes get visually longer/shorter as the orb is
    // resized, which is acceptable for a decorative element.
    rotate(ringRotationOuter, pivot = center) {
        drawCircle(
            color = JarvisCyan.copy(alpha = 0.18f),
            radius = outerR,
            center = center,
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(6f, 8f),
                    phase = 0f
                ),
                cap = StrokeCap.Round
            )
        )
    }

    // Mid cyan ring (full opacity, medium rotation) with a soft halo.
    rotate(ringRotationCyan, pivot = center) {
        drawCircle(
            color = JarvisCyan.copy(alpha = 0.85f),
            radius = midR,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = JarvisCyan.copy(alpha = 0.25f),
            radius = midR * 1.04f,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )
    }

    // Inner magenta ring (counter-rotation, brighter when active) with halo.
    val magentaAlpha = if (isActive) 1f else 0.7f
    rotate(ringRotationMagenta, pivot = center) {
        drawCircle(
            color = HereticMagenta.copy(alpha = magentaAlpha),
            radius = innerR,
            center = center,
            style = Stroke(width = 1.8.dp.toPx())
        )
        drawCircle(
            color = HereticMagenta.copy(alpha = 0.22f * magentaAlpha),
            radius = innerR * 1.06f,
            center = center,
            style = Stroke(width = 5.dp.toPx())
        )
    }

    // Plasma core: a radial gradient from HereticCore to transparent. The
    // gradient is what makes the orb read as a glowing sphere rather than a
    // stack of flat rings.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                HereticMagenta.copy(alpha = 0.85f),
                HereticCore.copy(alpha = 0.55f),
                Color.Transparent
            ),
            center = center,
            radius = coreR
        ),
        radius = coreR,
        center = center
    )

    // Sigil: a diamond outline with a cross inside, drawn in [JarvisCyan] for
    // high contrast against the magenta core. The sigil is rotated to match
    // the inner magenta ring so it tracks that ring visually. We rebuild the
    // sigil in pixel space every frame because the cached normalised paths
    // cannot be cheaply re-anchored in Compose's [Path] API; the geometry
    // is tiny (5 points for the diamond, 4 for the cross) so the cost is
    // negligible.
    rotate(ringRotationMagenta, pivot = center) {
        val diamond = Path().apply {
            moveTo(center.x, center.y - sigilR * 0.55f)
            lineTo(center.x + sigilR * 0.32f, center.y)
            lineTo(center.x, center.y + sigilR * 0.55f)
            lineTo(center.x - sigilR * 0.32f, center.y)
            close()
        }
        drawPath(
            path = diamond,
            color = JarvisCyan,
            style = Stroke(width = 1.4.dp.toPx())
        )
        val cross = Path().apply {
            moveTo(center.x, center.y - sigilR * 0.18f)
            lineTo(center.x, center.y + sigilR * 0.18f)
            moveTo(center.x - sigilR * 0.13f, center.y)
            lineTo(center.x + sigilR * 0.13f, center.y)
        }
        drawPath(
            path = cross,
            color = JarvisCyan,
            style = Stroke(
                width = 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }

    // A tiny centre dot to anchor the sigil.
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = 1.4.dp.toPx(),
        center = center
    )
}
