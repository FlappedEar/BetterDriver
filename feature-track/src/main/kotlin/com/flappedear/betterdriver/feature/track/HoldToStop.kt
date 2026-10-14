package com.flappedear.betterdriver.feature.track

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.textStyle
import kotlinx.coroutines.launch
import java.util.Locale

/** UI-16: Arek asked for 3 s instead of the proposed 2 s. */
internal const val HoldToStopMillis = 3_000

private const val MillisPerSecond = 1_000f
private const val FullCircle = 360f
private const val StartAngle = -90f

/**
 * Stops the session after a hold anywhere on [content] (UI-16 option A).
 * While the finger is down a ring fills over [HoldToStopMillis]; lifting the
 * finger earlier cancels it. A bump or a short tap does nothing.
 */
@Composable
internal fun HoldToStop(onStop: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val stop by rememberUpdatedState(onStop)
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val hold = scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(HoldToStopMillis, easing = LinearEasing))
                    stop()
                }
                waitForUpOrCancellation()
                if (hold.isActive) {
                    hold.cancel()
                    scope.launch { progress.snapTo(0f) }
                }
            }
        },
    ) {
        content()
        val p = progress.value
        if (p > 0f && p < 1f) {
            HoldRing(p, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun HoldRing(progress: Float, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    val remaining = (1f - progress) * HoldToStopMillis / MillisPerSecond
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(Color(0xEB000000))
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = FullCircle,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke),
            )
            drawArc(
                color = c.loss,
                startAngle = StartAngle,
                sweepAngle = FullCircle * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke),
            )
        }
        Text(
            "Hold to stop\n" + String.format(Locale.ROOT, "%.1f s", remaining),
            style = textStyle(18.dp),
            color = c.text,
            textAlign = TextAlign.Center,
        )
    }
}
