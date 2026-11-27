package com.apenv.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

// Shrinks a little while a finger is down and springs back. It only watches touches, so it can sit next to clickable.
internal fun Modifier.pressScale(to: Float = 0.97f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(
        if (pressed) to else 1f,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press"
    )
    this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                pressed = false
            }
        }
}

// Fades in and rises a little the first time it is shown.
internal fun Modifier.appear(delayMs: Int = 0, rise: Float = 14f, enabled: Boolean = true): Modifier =
    if (!enabled) this else composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(280, delayMillis = delayMs), label = "appear")
    this.graphicsLayer {
        alpha = a
        translationY = (1f - a) * rise
    }
}

// Starts a full screen width off to one side and speeds in until it meets its partner after
// durationMs, then gives a small pulse. Left tiles use fromLeft = true, right tiles false.
internal fun Modifier.slideTogether(fromLeft: Boolean, durationMs: Int = 500, enabled: Boolean = true): Modifier =
    if (!enabled) this else composed {
    val screenPx = LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val p by animateFloatAsState(
        if (go) 1f else 0f,
        tween(durationMs, easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)),
        label = "slide"
    )
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(durationMs.toLong())
        pulse.snapTo(0.94f)
        pulse.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
    }
    this.graphicsLayer {
        translationX = (1f - p) * screenPx * (if (fromLeft) -1f else 1f)
        scaleX = pulse.value
        scaleY = pulse.value
    }
}

// Timing of the home intro: the top cards wait DROP_DELAY_MS, fall for DROP_DURATION_MS, and the tiles get hit at HOME_IMPACT_MS.
internal const val DROP_DELAY_MS = 250
internal const val DROP_DURATION_MS = 640
internal const val HOME_IMPACT_MS = (DROP_DELAY_MS + DROP_DURATION_MS).toLong()
// OIV falls separately, starting when the big cards have landed, and takes only this long.
internal const val OIV_DROP_MS = 450
// OIV lands here and gives the tiles a second, smaller shove.
internal const val OIV_IMPACT_MS = HOME_IMPACT_MS + OIV_DROP_MS

// Falls in from above and "through" the screen: starts big and tilted (close to the viewer), speeds up
// like gravity while shrinking to its real size, then lands with a small squash.
internal fun Modifier.dropFromTop(delayMs: Int = DROP_DELAY_MS, durationMs: Int = DROP_DURATION_MS, enabled: Boolean = true): Modifier =
    if (!enabled) this else composed {
        val density = LocalDensity.current.density
        val screenPx = LocalConfiguration.current.screenHeightDp * density
        val p = remember { Animatable(0f) }
        val land = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            delay(delayMs.toLong())
            p.animateTo(1f, tween(durationMs, easing = CubicBezierEasing(0.55f, 0f, 1f, 0.45f)))
            land.snapTo(0.94f)
            land.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 420f))
        }
        this
            .zIndex(1f)
            .graphicsLayer {
                val t = 1f - p.value
                cameraDistance = 14f * density
                translationY = -t * screenPx
                rotationX = 28f * t
                val s = (1f + 1.3f * t) * land.value
                scaleX = s
                scaleY = s
                alpha = if (t > 0.98f) 0f else 1f
            }
    }

// Shoved by the hit of the falling cards. impact() goes 1 -> 0 with a wobble; every tile gets its own
// push (dx/dy in dp, rotation in degrees) depending on where it sits relative to the impact.
internal fun Modifier.shock(impact: () -> Float, dxDp: Float, dyDp: Float, rotDeg: Float, enabled: Boolean = true): Modifier =
    if (!enabled) this else composed {
        val density = LocalDensity.current.density
        this.graphicsLayer {
            val v = impact()
            translationX = v * dxDp * density
            translationY = v * dyDp * density
            rotationZ = v * rotDeg
        }
    }

// One wobble per element: starts atMs + delayMs after the intro begins (delay = how far the shock wave
// has to travel to reach that element), kicks to 1 and springs back to 0 with overshoot.
@Composable
internal fun rememberImpact(atMs: Long, delayMs: Long, enabled: Boolean, damping: Float = 0.4f, stiffness: Float = 260f): () -> Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (enabled) {
            delay(atMs + delayMs)
            a.snapTo(1f)
            a.animateTo(0f, spring(dampingRatio = damping, stiffness = stiffness))
        }
    }
    return { a.value }
}
