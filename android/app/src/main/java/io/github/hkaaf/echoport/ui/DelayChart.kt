package io.github.hkaaf.echoport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hkaaf.echoport.R
import io.github.hkaaf.echoport.engine.DelaySample
import kotlin.math.abs
import kotlin.math.ceil

private const val WINDOW_MS = 60_000f

/** Live line chart of the player's estimated delay; touch and drag to read a point. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DelayChart(samples: List<DelaySample>) {
    val ep = LocalEp.current
    val measurer = rememberTextMeasurer()
    // Canvas text does not inherit the theme font, so pass it on (Vazirmatn in Persian).
    val family = MaterialTheme.typography.bodyMedium.fontFamily
    var touchX by remember { mutableStateOf<Float?>(null) }

    val last = samples.lastOrNull()
    val avg = if (samples.isEmpty()) 0 else samples.sumOf { it.delayMs } / samples.size
    val max = samples.maxOfOrNull { it.delayMs } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Hint(stringResource(R.string.delay_current))
                Text(if (last != null) "${last.delayMs} ms" else "—", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            StatTile(stringResource(R.string.delay_average), if (last != null) "$avg ms" else "—")
            StatTile(stringResource(R.string.delay_max), if (last != null) "$max ms" else "—")
            StatTile(stringResource(R.string.delay_ping), last?.let { "${it.rttMs} ms" } ?: "—")
            StatTile(stringResource(R.string.delay_buffer), last?.let { "${it.bufferMs} ms" } ?: "—")
        }

        val description = stringResource(R.string.delay_aria, last?.delayMs ?: 0, avg, max)
        val nowLabel = stringResource(R.string.delay_now)
        val agoLabel = stringResource(R.string.delay_ago, 60)
        val agoFormat = stringResource(R.string.delay_ago)
        Canvas(
            Modifier.fillMaxWidth().height(170.dp)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touchX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            touchX = change.position.x
                            change.consume()
                        }
                        touchX = null
                    }
                },
        ) {
            val left = 40.dp.toPx(); val right = 6.dp.toPx(); val top = 8.dp.toPx(); val bottom = 20.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom
            val now = last?.timeMs ?: 0L
            val peak = maxOf(50, max)
            val step = if (peak <= 100) 25 else if (peak <= 400) 100 else 250
            val yMax = (ceil(peak * 1.1 / step) * step).toFloat()
            fun x(t: Long) = left + w * (1 - (now - t) / WINDOW_MS)
            fun y(ms: Int) = top + h * (1 - ms / yMax)
            val axis = TextStyle(color = ep.muted, fontSize = 10.sp, fontFamily = family)

            for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val v = (yMax * f).toInt()
                drawLine(ep.border, Offset(left, y(v)), Offset(left + w, y(v)), strokeWidth = 1.dp.toPx())
                val label = measurer.measure("$v", axis)
                drawText(label, topLeft = Offset(left - 6.dp.toPx() - label.size.width, y(v) - label.size.height / 2))
            }
            val agoText = measurer.measure(agoLabel, axis)
            val nowText = measurer.measure(nowLabel, axis)
            drawText(agoText, topLeft = Offset(left, size.height - agoText.size.height))
            drawText(nowText, topLeft = Offset(left + w - nowText.size.width, size.height - nowText.size.height))

            if (samples.size > 1) {
                val line = Path().apply {
                    samples.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.timeMs), y(s.delayMs)) else lineTo(x(s.timeMs), y(s.delayMs)) }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(samples.last().timeMs), y(0)); lineTo(x(samples.first().timeMs), y(0)); close()
                }
                drawPath(area, ep.accent.copy(alpha = 0.12f))
                drawPath(line, ep.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            val tx = touchX
            if (tx != null && samples.isNotEmpty()) {
                val s = samples.minBy { abs(x(it.timeMs) - tx) }
                val sx = x(s.timeMs); val sy = y(s.delayMs)
                drawLine(ep.muted, Offset(sx, top), Offset(sx, top + h), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawCircle(ep.surface, radius = 7.dp.toPx(), center = Offset(sx, sy))
                drawCircle(ep.accent, radius = 5.dp.toPx(), center = Offset(sx, sy))
                val secs = ((now - s.timeMs) / 1000).toInt()
                val tip = measurer.measure("${s.delayMs} ms · ${if (secs == 0) nowLabel else agoFormat.format(secs)}",
                    TextStyle(color = ep.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = family))
                val pad = 6.dp.toPx()
                val bx = (sx - tip.size.width / 2f).coerceIn(left, left + w - tip.size.width - 2 * pad)
                val by = (sy - tip.size.height - 3 * pad).coerceAtLeast(0f)
                drawRoundRect(ep.surface2, topLeft = Offset(bx, by),
                    size = androidx.compose.ui.geometry.Size(tip.size.width + 2 * pad, tip.size.height + 2 * pad),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad, pad))
                drawText(tip, topLeft = Offset(bx + pad, by + pad))
            }
        }
        if (samples.isEmpty()) Hint(stringResource(R.string.delay_empty))
        Hint(stringResource(R.string.delay_hint))
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Hint(label)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
