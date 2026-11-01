package com.btcsignal.app.engine.indicators

import com.btcsignal.app.data.model.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * Indicator implementations used by the Core Signal Engine.
 *
 * IMPLEMENTATION NOTE / DOCUMENTED AMBIGUITY (per master-prompt rule "if ambiguous,
 * do not guess — document it"): the Strategy Database specifies indicator NAME and
 * PERIOD (e.g. "RSI(7)", "ADX(14)") but does not specify the smoothing method used to
 * produce the reference win-rate numbers. RSI/ADX/ATR are implemented here with the
 * standard Wilder smoothing method, the universal industry-default for these three
 * indicators. EMA, SMA, MACD, CCI, Williams %R, Stochastic, Bollinger Bands, OBV and
 * ROC have a single unambiguous standard definition and are implemented that way.
 * This choice is not an optimization of any strategy — the same indicator engine is
 * used identically for every strategy, live and backtest alike.
 *
 * All functions take chronologically ordered, CLOSED candles only. Callers (see
 * MarketDataStore / CoreSignalEngine) are responsible for never passing the still-
 * forming candle, which is how look-ahead bias is prevented at the source.
 */
object Indicators {

    // ---------- Moving averages ----------

    fun sma(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        return values.takeLast(period).average()
    }

    /** EMA series aligned to [values] (same length), SMA-seeded for the first [period] values. */
    fun emaSeries(values: List<Double>, period: Int): List<Double?> {
        if (values.size < period) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        val k = 2.0 / (period + 1)
        val seed = values.subList(0, period).average()
        result[period - 1] = seed
        var prev = seed
        for (i in period until values.size) {
            val v = values[i] * k + prev * (1 - k)
            result[i] = v
            prev = v
        }
        return result
    }

    fun ema(values: List<Double>, period: Int): Double? = emaSeries(values, period).lastOrNull()

    // ---------- RSI (Wilder) ----------

    fun rsi(closes: List<Double>, period: Int): Double? {
        if (closes.size < period + 1) return null
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) avgGain += diff else avgLoss -= diff
        }
        avgGain /= period
        avgLoss /= period
        for (i in (period + 1) until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    // ---------- Stochastic %K / %D ----------

    fun stochastic(candles: List<Candle>, kPeriod: Int, dPeriod: Int): Pair<Double, Double>? {
        if (candles.size < kPeriod + dPeriod - 1) return null
        val rawK = ArrayList<Double>()
        for (end in (kPeriod - 1) until candles.size) {
            val window = candles.subList(end - kPeriod + 1, end + 1)
            val highest = window.maxOf { it.high }
            val lowest = window.minOf { it.low }
            val close = window.last().close
            val k = if (highest == lowest) 50.0 else (close - lowest) / (highest - lowest) * 100.0
            rawK.add(k)
        }
        if (rawK.size < dPeriod) return null
        val kValue = rawK.last()
        val dValue = rawK.takeLast(dPeriod).average()
        return kValue to dValue
    }

    // ---------- CCI ----------

    fun cci(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period) return null
        val window = candles.takeLast(period)
        val typicalPrices = window.map { (it.high + it.low + it.close) / 3.0 }
        val smaTp = typicalPrices.average()
        val meanDeviation = typicalPrices.map { abs(it - smaTp) }.average()
        if (meanDeviation == 0.0) return 0.0
        val currentTp = typicalPrices.last()
        return (currentTp - smaTp) / (0.015 * meanDeviation)
    }

    // ---------- Williams %R ----------

    fun williamsR(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period) return null
        val window = candles.takeLast(period)
        val highest = window.maxOf { it.high }
        val lowest = window.minOf { it.low }
        val close = window.last().close
        if (highest == lowest) return -50.0
        return (highest - close) / (highest - lowest) * -100.0
    }

    // ---------- MACD ----------

    data class Macd(val macdLine: Double, val signalLine: Double, val histogram: Double)

    fun macd(closes: List<Double>, fast: Int, slow: Int, signal: Int): Macd? {
        if (closes.size < slow + signal - 1) return null
        val fastSeries = emaSeries(closes, fast)
        val slowSeries = emaSeries(closes, slow)
        val macdSeries = ArrayList<Double>()
        for (i in closes.indices) {
            val f = fastSeries.getOrNull(i)
            val s = slowSeries.getOrNull(i)
            if (f != null && s != null) macdSeries.add(f - s)
        }
        if (macdSeries.size < signal) return null
        val signalSeries = emaSeries(macdSeries, signal)
        val macdLine = macdSeries.last()
        val signalLine = signalSeries.last() ?: return null
        return Macd(macdLine, signalLine, macdLine - signalLine)
    }

    // ---------- ADX + DI (Wilder) ----------

    data class Adx(val adx: Double, val plusDi: Double, val minusDi: Double)

    fun adxDi(candles: List<Candle>, period: Int): Adx? {
        if (candles.size < period * 2) return null

        val trList = ArrayList<Double>()
        val plusDmList = ArrayList<Double>()
        val minusDmList = ArrayList<Double>()

        for (i in 1 until candles.size) {
            val cur = candles[i]
            val prev = candles[i - 1]
            val highDiff = cur.high - prev.high
            val lowDiff = prev.low - cur.low
            val plusDm = if (highDiff > lowDiff && highDiff > 0) highDiff else 0.0
            val minusDm = if (lowDiff > highDiff && lowDiff > 0) lowDiff else 0.0
            val tr = max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close)))
            trList.add(tr)
            plusDmList.add(plusDm)
            minusDmList.add(minusDm)
        }

        var smTr = trList.take(period).sum()
        var smPlusDm = plusDmList.take(period).sum()
        var smMinusDm = minusDmList.take(period).sum()

        val dxList = ArrayList<Double>()
        fun computeDx(tr: Double, plusDm: Double, minusDm: Double): Double {
            val plusDi = if (tr == 0.0) 0.0 else 100.0 * plusDm / tr
            val minusDi = if (tr == 0.0) 0.0 else 100.0 * minusDm / tr
            val sum = plusDi + minusDi
            return if (sum == 0.0) 0.0 else 100.0 * abs(plusDi - minusDi) / sum
        }
        dxList.add(computeDx(smTr, smPlusDm, smMinusDm))

        for (i in period until trList.size) {
            smTr = smTr - (smTr / period) + trList[i]
            smPlusDm = smPlusDm - (smPlusDm / period) + plusDmList[i]
            smMinusDm = smMinusDm - (smMinusDm / period) + minusDmList[i]
            dxList.add(computeDx(smTr, smPlusDm, smMinusDm))
        }

        if (dxList.size < period) return null
        // Wilder's ADX is a recursive smoothing of the DX series, NOT a simple moving
        // average of the last `period` DX values (Bug Report 1.3). The first ADX value
        // is seeded as the SMA of the first `period` DX values; every DX after that
        // recursively updates the running ADX exactly the way atrSeries() below already
        // smooths TR - so ADX carries the full weighted history of the trend, with the
        // standard exponentially-decaying memory, instead of a hard `period`-candle window
        // that forgets everything older than that in one step.
        var adxValue = dxList.take(period).average()
        for (i in period until dxList.size) {
            adxValue = (adxValue * (period - 1) + dxList[i]) / period
        }
        val finalPlusDi = if (smTr == 0.0) 0.0 else 100.0 * smPlusDm / smTr
        val finalMinusDi = if (smTr == 0.0) 0.0 else 100.0 * smMinusDm / smTr
        return Adx(adxValue, finalPlusDi, finalMinusDi)
    }

    // ---------- ATR (Wilder) ----------

    fun atrSeries(candles: List<Candle>, period: Int): List<Double?> {
        if (candles.size < period + 1) return List(candles.size) { null }
        val trList = ArrayList<Double>()
        for (i in 1 until candles.size) {
            val cur = candles[i]
            val prev = candles[i - 1]
            trList.add(max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close))))
        }
        val result = MutableList<Double?>(candles.size) { null }
        var atr = trList.take(period).average()
        result[period] = atr
        for (i in period until trList.size) {
            atr = (atr * (period - 1) + trList[i]) / period
            result[i + 1] = atr
        }
        return result
    }

    /** Percentile rank (0-100) of the latest value within the trailing [lookback] values (inclusive). */
    fun percentileRank(values: List<Double>, lookback: Int): Double? {
        if (values.size < lookback) return null
        val window = values.takeLast(lookback)
        val latest = window.last()
        val countBelowOrEqual = window.count { it <= latest }
        return countBelowOrEqual.toDouble() / window.size * 100.0
    }

    // ---------- Momentum Ratio V2 ----------

    data class MomentumRatioV2(val longRatio: Double, val shortRatio: Double)

    /**
     * NEXA V2 definition (audit-doc "01_ANDROID_INTEGRATION_PROMPT" section 19):
     *   LONG numerator  = Minute-1 High - 5M Open
     *   SHORT numerator = 5M Open - Minute-1 Low
     * both normalized by the causal local ATR(3) of the target timeframe (candles here
     * must already be the CLOSED-candle list up to, but not including, the currently
     * forming 5m candle - the same causal convention every other indicator in this file
     * uses). Returns null if ATR(3) cannot yet be computed (insufficient warmup) or is
     * zero (would make the ratio undefined) - callers must treat that as UNAVAILABLE,
     * never substitute a fallback value.
     */
    fun momentumRatioV2(candles: List<Candle>, candleOpen: Double, minute1High: Double, minute1Low: Double): MomentumRatioV2? {
        val atr3 = atrSeries(candles, 3).lastOrNull() ?: return null
        if (atr3 == 0.0) return null
        val longRatio = (minute1High - candleOpen) / atr3
        val shortRatio = (candleOpen - minute1Low) / atr3
        return MomentumRatioV2(longRatio, shortRatio)
    }

    // ---------- OBV ----------

    fun obvSeries(candles: List<Candle>): List<Double> {
        val result = ArrayList<Double>(candles.size)
        var obv = 0.0
        result.add(obv)
        for (i in 1 until candles.size) {
            obv += when {
                candles[i].close > candles[i - 1].close -> candles[i].volume
                candles[i].close < candles[i - 1].close -> -candles[i].volume
                else -> 0.0
            }
            result.add(obv)
        }
        return result
    }

    /** Slope (least-squares) of OBV over the trailing [lookback] closed candles. */
    fun obvSlope(candles: List<Candle>, lookback: Int): Double? {
        if (candles.size < lookback) return null
        val obv = obvSeries(candles).takeLast(lookback)
        val n = obv.size
        val xMean = (n - 1) / 2.0
        val yMean = obv.average()
        var num = 0.0
        var den = 0.0
        for (i in obv.indices) {
            num += (i - xMean) * (obv[i] - yMean)
            den += (i - xMean) * (i - xMean)
        }
        return if (den == 0.0) 0.0 else num / den
    }

    // ---------- ROC ----------

    fun roc(closes: List<Double>, period: Int): Double? = rocSeries(closes, period).lastOrNull()

    /**
     * ROC series aligned to [closes] (same length, null before index [period]). Extracted
     * as a series (Bug Report 3.2) so MarketRegimeClassifier's momentum-percentile lookback
     * - which needs ROC at every trailing point, not just the latest one - can share this
     * single implementation instead of re-deriving the same formula by hand, which risked
     * the two copies silently drifting apart if either one were ever changed alone.
     */
    fun rocSeries(closes: List<Double>, period: Int): List<Double?> {
        if (closes.size < period + 1) return List(closes.size) { null }
        return closes.indices.map { i ->
            if (i < period) null else {
                val past = closes[i - period]
                if (past == 0.0) null else (closes[i] - past) / past * 100.0
            }
        }
    }

    // ---------- Bollinger %B ----------

    fun bollingerPercentB(closes: List<Double>, period: Int, std: Double): Double? {
        if (closes.size < period) return null
        val window = closes.takeLast(period)
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / period
        val sd = Math.sqrt(variance)
        val upper = mean + std * sd
        val lower = mean - std * sd
        if (upper == lower) return 0.5
        val price = closes.last()
        return (price - lower) / (upper - lower)
    }

    // ---------- Candle patterns ----------

    /**
     * Marubozu: large real body with negligible wicks. Returns +1 (bullish), -1 (bearish)
     * or null if the last closed candle doesn't qualify. Threshold: body >= 90% of the
     * candle's total range — the standard definition, applied identically regardless of
     * direction (not tuned per-strategy).
     */
    fun marubozuDirection(candle: Candle): Int? {
        val range = candle.high - candle.low
        if (range <= 0) return null
        val body = abs(candle.close - candle.open)
        if (body / range < 0.90) return null
        return if (candle.close > candle.open) 1 else if (candle.close < candle.open) -1 else null
    }
}
