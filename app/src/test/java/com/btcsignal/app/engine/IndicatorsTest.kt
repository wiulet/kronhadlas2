package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import com.btcsignal.app.engine.indicators.Indicators
import org.junit.Assert.*
import org.junit.Test

class IndicatorsTest {

    private fun candle(open: Double, high: Double, low: Double, close: Double, vol: Double = 1.0, t: Long = 0L) =
        Candle(t, open, high, low, close, vol, t + 60_000, true)

    @Test
    fun `RSI is 100 when there are no losses`() {
        val closes = (1..20).map { it.toDouble() } // strictly increasing
        val rsi = Indicators.rsi(closes, 14)
        assertNotNull(rsi)
        assertEquals(100.0, rsi!!, 0.001)
    }

    @Test
    fun `RSI is 0 when there are no gains`() {
        val closes = (20 downTo 1).map { it.toDouble() } // strictly decreasing
        val rsi = Indicators.rsi(closes, 14)
        assertNotNull(rsi)
        assertEquals(0.0, rsi!!, 0.001)
    }

    @Test
    fun `RSI returns null with insufficient data`() {
        val closes = listOf(1.0, 2.0, 3.0)
        assertNull(Indicators.rsi(closes, 14))
    }

    @Test
    fun `EMA seeds with SMA and converges toward recent values`() {
        val closes = List(30) { 100.0 } + List(10) { 200.0 }
        val ema = Indicators.ema(closes, 10)
        assertNotNull(ema)
        assertTrue("EMA should have moved toward the new higher values", ema!! > 100.0)
    }

    @Test
    fun `Williams R at extremes`() {
        val candles = (1..14).map { candle(it.toDouble(), it + 1.0, it - 1.0, it.toDouble(), t = it * 60_000L) }
        val wr = Indicators.williamsR(candles, 14)
        assertNotNull(wr)
        // Last close is the highest close in the window -> Williams %R near 0 (overbought)
        assertTrue(wr!! > -30)
    }

    @Test
    fun `marubozu requires body at least 90 percent of range`() {
        val strongBull = candle(open = 100.0, high = 110.0, low = 99.9, close = 109.9, t = 0)
        assertEquals(1, Indicators.marubozuDirection(strongBull))

        val doji = candle(open = 100.0, high = 105.0, low = 95.0, close = 100.2, t = 0)
        assertNull(Indicators.marubozuDirection(doji))
    }

    @Test
    fun `percentile rank of the max value in the window is 100`() {
        val values = (1..100).map { it.toDouble() }
        val pct = Indicators.percentileRank(values, 100)
        assertNotNull(pct)
        assertEquals(100.0, pct!!, 0.001)
    }

    @Test
    fun `OBV slope is positive on a rising-close series with volume`() {
        val candles = (1..10).map { i ->
            candle(open = i.toDouble(), high = i + 0.5, low = i - 0.5, close = i.toDouble(), vol = 10.0, t = i * 60_000L)
        }
        val slope = Indicators.obvSlope(candles, 10)
        assertNotNull(slope)
        assertTrue(slope!! > 0)
    }

    @Test
    fun `Momentum Ratio V2 favors the side minute-1 pushed harder, normalized by ATR3`() {
        // flat, low-volatility history so ATR(3) is small and stable
        val history = (1..10).map { i -> candle(open = 100.0, high = 100.5, low = 99.5, close = 100.0, t = i * 300_000L) }
        // minute-1 pushed strongly above the 5m open -> longRatio should dominate shortRatio
        val ratio = Indicators.momentumRatioV2(history, candleOpen = 100.0, minute1High = 103.0, minute1Low = 99.8)
        assertNotNull(ratio)
        assertTrue("long push should score higher than the (tiny) opposite-side wick", ratio!!.longRatio > ratio.shortRatio)
        assertTrue("a 3-point push against ~1-point ATR should clear a modest threshold", ratio.longRatio > 1.0)
    }

    @Test
    fun `Momentum Ratio V2 is unavailable before ATR3 has enough warmup`() {
        val tooShort = (1..2).map { i -> candle(open = 100.0, high = 100.5, low = 99.5, close = 100.0, t = i * 300_000L) }
        assertNull(Indicators.momentumRatioV2(tooShort, candleOpen = 100.0, minute1High = 103.0, minute1Low = 99.8))
    }

    @Test
    fun `ADX uses Wilder recursive smoothing, not a plain trailing average of the last period DX values`() {
        // Phase 1 (39 steps): a clean, constant uptrend - the 20-wide high/low range
        // shifts up by 10 every candle, giving a perfectly constant DX of 100.0 the
        // whole way through. Phase 2 (28 steps): the same range now zigzags -10/+10/
        // -10/... every candle, so directional pressure cancels step to step and DX
        // drops toward the low teens. By the final candle, all 14 of the most recent DX
        // values come from Phase 2 alone.
        var high = 110.0
        var low = 90.0
        val candles = ArrayList<Candle>()
        fun push(t: Long) {
            val mid = (high + low) / 2.0
            candles.add(Candle(t, mid, high, low, mid, 1.0, t + 60_000, true))
        }
        push(0L)
        for (i in 0 until 39) {
            high += 10.0; low += 10.0
            push((i + 1) * 60_000L)
        }
        var sign = -1.0
        for (i in 0 until 28) {
            high += sign * 10.0; low += sign * 10.0
            push((40 + i) * 60_000L)
            sign *= -1.0
        }

        val adx = Indicators.adxDi(candles, 14)
        assertNotNull(adx)
        // Correct Wilder ADX(14) on this exact series, from an independent reference
        // computation of the standard definition.
        assertEquals(36.856, adx!!.adx, 0.01)
        // The old bug (a plain average of only the last 14 DX values, all from Phase 2)
        // would have read ~20.46 here - on the wrong side of the regime threshold of 25,
        // and forgetting the 39-candle trend that just preceded it almost entirely.
        assertTrue(
            "Fixed ADX (${adx.adx}) must sit well above the old buggy trailing-average value (~20.46), " +
                "and on the correct side of the regime threshold of 25",
            adx.adx > 30.0
        )
    }

    @Test
    fun `adxDi returns null with fewer than 2 times period candles`() {
        val candles = (1..20).map { candle(it.toDouble(), it + 1.0, it - 1.0, it.toDouble(), t = it * 60_000L) }
        assertNull(Indicators.adxDi(candles, 14)) // needs 28, only 20 given
    }
}
