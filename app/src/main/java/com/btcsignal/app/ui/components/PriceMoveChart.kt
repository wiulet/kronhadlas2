package com.btcsignal.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btcsignal.app.live.LiveEngineState
import com.btcsignal.app.ui.theme.AccentPurple
import com.btcsignal.app.ui.theme.AmberWarning
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TextPrimary
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private const val CANDLE_MS = 5 * 60 * 1000L

/** Gap between two consecutive logged ticks beyond which we no longer trust the
 *  straight line between them as an actual observed price move — see drawPriceChart. */
private const val GAP_THRESHOLD_MS = 8_000f

/**
 * Live "price move since candle open" chart, ported from the magic.z1 app's
 * TargetChart widget. Shows the price path since the current 5m candle opened
 * relative to a dashed line at the candle's open price (green fill above,
 * red fill below the open), with a glowing live-price line, restyled to this
 * app's glass theme and placed where the old standalone "Current Prediction"
 * card used to sit.
 */
@Composable
fun PriceMoveChart(
    candleOpenPrice: Double,
    candleOpenTime: Long,
    isConnected: Boolean,
    /** Where the active signal fired: x = elapsed ms since candle open, y = price.
     *  Rendered as a solid dot instead of a line so it marks the exact moment/price,
     *  not a whole row. */
    signalPoint: Offset? = null,
    modifier: Modifier = Modifier
) {
    val target = candleOpenPrice.takeIf { it > 0.0 }

    // x = elapsed ms, y = price. Mirrors LiveEngineState.candlePriceLog -- which
    // LiveMonitoringService writes unconditionally on every real price tick, regardless
    // of which screen is on-screen or whether the app is backgrounded -- into
    // Compose-observable state, instead of walking a separate composable-local path.
    // That means switching bottom-nav tabs or minimizing the app can never leave this
    // chart out of sync with reality: whenever it's next composed, the very first poll
    // below copies over the complete path recorded while it was away, in one shot, with
    // no gap and no jump. It also means every point reflects an actual observed tick
    // rather than a fixed-interval sample of "whatever the current price happens to be
    // right now", so genuine small in-between moves aren't smoothed away. See
    // candlePriceLog's KDoc.
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(candleOpenTime) {
        while (isActive) {
            points = synchronized(LiveEngineState.candlePriceLog) {
                ArrayList(LiveEngineState.candlePriceLog)
            }
            delay(200)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Price Move Since Candle Open", style = MaterialTheme.typography.titleMedium)
            LiveBadge()
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            val t = target
            if (t == null || points.isEmpty()) return@Canvas
            drawPriceChart(points = points, target = t, signalPoint = signalPoint)
        }

        Text(
            if (isConnected) "Live connection established" else "Connecting to Binance...",
            color = TextSecondary, fontSize = 11.sp
        )
    }
}

/**
 * Frozen, non-live rendering of the same "Price Move Since Candle Open" chart, built from
 * a signal's saved [pricePath] snapshot (captured by LiveMonitoringService at candle close
 * and persisted on the Signal row) instead of a currently-walking live path. Used by
 * HistoryScreen so tapping a past signal shows exactly what the live chart looked like
 * when that candle closed, without needing to store an actual screenshot bitmap.
 */
@Composable
fun PriceMoveSnapshotChart(
    pricePath: List<Offset>,
    candleOpenPrice: Double,
    signalPoint: Offset?,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        if (candleOpenPrice <= 0.0 || pricePath.isEmpty()) return@Canvas
        drawPriceChart(points = pricePath, target = candleOpenPrice, signalPoint = signalPoint)
    }
}

/** Shared drawing code for both the live [PriceMoveChart] and the frozen
 *  [PriceMoveSnapshotChart] -- green/red fill around [target], grid + $ labels, the
 *  dashed "Target" line at [target], a solid amber dot at [signalPoint] if present
 *  marking exactly where/when the signal fired, and the purple price path ending in
 *  a dot at its last point. */
private fun DrawScope.drawPriceChart(
    points: List<Offset>,
    target: Double,
    signalPoint: Offset?
) {
    val t = target
    val padRight = 62.dp.toPx()
    val padTop = 4.dp.toPx()
    val padBottom = 4.dp.toPx()
    val chartW = (size.width - padRight).coerceAtLeast(1f)
    val chartH = (size.height - padTop - padBottom).coerceAtLeast(1f)

    var maxDev = 8.0
    points.forEach { p -> maxDev = max(maxDev, abs(p.y - t.toFloat()).toDouble()) }
    // Also make sure the signal-entry dot (if any) fits on-screen even before
    // the live price has walked that far, so it's visible the moment a signal fires.
    signalPoint?.let { maxDev = max(maxDev, abs(it.y - t.toFloat()).toDouble()) }
    maxDev *= 1.35
    val yMin = t - maxDev
    val yMax = t + maxDev
    val span = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0

    fun xOf(elapsed: Float) = (elapsed / CANDLE_MS.toFloat()) * chartW
    fun yOf(price: Float) = padTop + (1f - ((price - yMin) / span).toFloat()) * chartH
    val targetY = yOf(t.toFloat())

    // green gradient above the open price, red gradient below it
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(GreenSignal.copy(alpha = 0.22f), GreenSignal.copy(alpha = 0.02f)),
            startY = padTop, endY = targetY.coerceAtLeast(padTop)
        ),
        topLeft = Offset(0f, padTop),
        size = Size(chartW, (targetY - padTop).coerceAtLeast(0f))
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(RedSignal.copy(alpha = 0.02f), RedSignal.copy(alpha = 0.20f)),
            startY = targetY, endY = (padTop + chartH).coerceAtLeast(targetY)
        ),
        topLeft = Offset(0f, targetY),
        size = Size(chartW, (padTop + chartH - targetY).coerceAtLeast(0f))
    )

    // grid lines + $ price labels
    val step = niceStep(span)
    var v = ceil(yMin / step) * step
    val gridPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#A6A2B1")
        textSize = 11.sp.toPx()
        isAntiAlias = true
    }
    while (v <= yMax) {
        val y = yOf(v.toFloat())
        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = Offset(0f, y), end = Offset(chartW, y), strokeWidth = 1f
        )
        drawContext.canvas.nativeCanvas.drawText(
            "$" + "%,.0f".format(v), chartW + 8.dp.toPx(), y + 4.dp.toPx(), gridPaint
        )
        v += step
    }

    // dashed "open price" target line + "Target" pill
    drawLine(
        color = Color.White.copy(alpha = 0.38f),
        start = Offset(0f, targetY), end = Offset(chartW, targetY),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    )
    // Dark metal "Target" pill, thin accent edge — matches the gothic panel
    // border language used everywhere else instead of the old flat navy chip.
    drawRect(
        color = Color(0xFF121218),
        topLeft = Offset(chartW + 3.dp.toPx(), targetY - 10.dp.toPx()),
        size = Size(padRight - 6.dp.toPx(), 20.dp.toPx())
    )
    drawRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(chartW + 3.dp.toPx(), targetY - 10.dp.toPx()),
        size = Size(padRight - 6.dp.toPx(), 20.dp.toPx()),
        style = Stroke(width = 1f)
    )
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 11.sp.toPx()
        isFakeBoldText = true
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText("Target", chartW + 8.dp.toPx(), targetY + 4.dp.toPx(), labelPaint)

    // Glowing live-price path. Ticks normally arrive every 1-2s; a gap much longer than
    // that between two consecutive logged points means no live data was received for a
    // stretch (a stalled connection, briefly backgrounded app, etc.), not that price
    // truly sat still. Drawing straight through it at full strength made a data outage
    // look like a confident, precisely-measured flat price move. Segments spanning an
    // unusually large gap are drawn thinner/dimmer and dashed instead, so a gap reads
    // visually as "no data" rather than "this is what the price did".
    if (points.size >= 2) {
        val normalPath = Path()
        var started = false
        for (i in 1 until points.size) {
            val prev = points[i - 1]; val cur = points[i]
            val gapMs = cur.x - prev.x
            val x0 = xOf(prev.x); val y0 = yOf(prev.y)
            val x1 = xOf(cur.x); val y1 = yOf(cur.y)
            if (gapMs > GAP_THRESHOLD_MS) {
                drawLine(
                    color = AccentPurple.copy(alpha = 0.35f),
                    start = Offset(x0, y0), end = Offset(x1, y1),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                started = false
            } else {
                if (!started) { normalPath.moveTo(x0, y0); started = true }
                normalPath.lineTo(x1, y1)
            }
        }
        drawPath(
            path = normalPath, color = AccentPurple,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
    points.lastOrNull()?.let { last ->
        val lx = xOf(last.x); val ly = yOf(last.y)
        drawCircle(AccentPurple.copy(alpha = 0.35f), radius = 12.dp.toPx(), center = Offset(lx, ly))
        drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(lx, ly))
        drawCircle(AccentPurple, radius = 4.dp.toPx(), center = Offset(lx, ly), style = Stroke(width = 2.dp.toPx()))
    }

    // solid amber dot at the exact point (time + price) the active signal fired at,
    // replacing the old full-width line so it marks a moment, not a whole row. Drawn
    // last so it always stays visible on top of the price path.
    signalPoint?.let { sp ->
        val sx = xOf(sp.x)
        val sy = yOf(sp.y)
        drawCircle(AmberWarning.copy(alpha = 0.30f), radius = 11.dp.toPx(), center = Offset(sx, sy))
        drawCircle(AmberWarning, radius = 5.dp.toPx(), center = Offset(sx, sy))
        drawCircle(Color.White, radius = 2.dp.toPx(), center = Offset(sx, sy))
    }
}

@Composable
private fun LiveBadge() {
    val infinite = rememberInfiniteTransition(label = "priceMoveChartPulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseAlpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .glassPanel(RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("Binance Live", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(GreenSignal.copy(alpha = alpha), CircleShape)
        )
    }
}

private fun niceStep(range: Double): Double {
    val rough = range / 4.0
    val mag = 10.0.pow(floor(log10(if (rough > 0.0) rough else 1.0)))
    val norm = rough / mag
    val step = when {
        norm < 1.5 -> 1.0
        norm < 3.0 -> 2.0
        norm < 7.0 -> 5.0
        else -> 10.0
    }
    return step * mag
}
