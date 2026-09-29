package dev.cued.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.cued.app.data.ScrubberMode
import dev.cued.app.playback.SpectrumBus
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Teal
import dev.cued.app.ui.theme.spectrumColor

/**
 * The song-position control. Two looks, same gesture: tap or drag to seek.
 *  - STANDARD: a plain progress bar.
 *  - REACTIVE_SPECTROGRAM: live spectrum bars; the position is the overlaid line.
 */
@Composable
fun PositionScrubber(
    mode: ScrubberMode,
    positionMs: Long,
    durationMs: Long,
    bus: SpectrumBus?,
    visualDelayMs: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 96,
) {
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val fraction = if (dragFraction >= 0f) dragFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val gestures = Modifier
        .pointerInput(durationMs) {
            detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong().coerceIn(0L, durationMs)) }
        }
        .pointerInput(durationMs) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { if (durationMs > 0 && dragFraction >= 0f) onSeek((dragFraction * durationMs).toLong()); dragFraction = -1f },
                onDragCancel = { dragFraction = -1f },
                onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f); change.consume() },
            )
        }

    Box(modifier.fillMaxWidth().height(height.dp).then(gestures)) {
        when (mode) {
            ScrubberMode.STANDARD -> StandardBar(fraction)
            ScrubberMode.REACTIVE_SPECTROGRAM -> ReactiveBars(bus, visualDelayMs, fraction)
        }
    }
}

@Composable
private fun StandardBar(fraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val barH = 6.dp.toPx()
        val y = size.height / 2 - barH / 2
        drawRoundRect(Ink3, topLeft = Offset(0f, y), size = Size(size.width, barH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2))
        drawRoundRect(Teal, topLeft = Offset(0f, y), size = Size(size.width * fraction, barH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2))
        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
    }
}

@Composable
private fun ReactiveBars(bus: SpectrumBus?, visualDelayMs: Int, fraction: Float) {
    val bands = bus?.bands ?: 48
    var frame by remember { mutableStateOf(FloatArray(bands)) }
    LaunchedEffect(bus, visualDelayMs) {
        if (bus == null) return@LaunchedEffect
        val buf = FloatArray(bands)
        val display = FloatArray(bands)
        while (true) {
            withFrameNanos { }
            val fresh = bus.read(visualDelayMs.toLong(), buf)
            for (i in 0 until bands) {
                val target = if (fresh) buf[i] else 0f
                display[i] = if (target > display[i]) target else display[i] * 0.86f
            }
            frame = display.copyOf()
        }
    }
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        drawRect(Ink3.copy(alpha = 0.6f))
        val n = frame.size
        val gap = 1.5f
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val v = frame[i]
            val h = (size.height * v).coerceAtLeast(2f)
            drawRect(spectrumColor(v), topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h))
        }
        drawPlayhead(fraction)
    }
}

private fun DrawScope.drawPlayhead(fraction: Float) {
    val x = size.width * fraction
    drawRect(Color.White.copy(alpha = 0.9f), topLeft = Offset(x - 1f, 0f), size = Size(2f, size.height))
    drawCircle(Amber, radius = 5f, center = Offset(x, size.height - 5f))
}
