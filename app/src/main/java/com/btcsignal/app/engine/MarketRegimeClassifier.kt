package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.indicators.Indicators
import kotlin.math.abs

/**
 * Implements the three independent, causal market-regime dimensions exactly as defined
 * in section 1.5 of the Strategy Research Report:
 *
 *   Trend:      ADX(14, 5m) >= 25 + DI direction; otherwise Sideways.
 *   Volatility: percentile rank of ATR%(14, 5m) over the trailing 100 closed 5m candles.
 *               High = percentile > 70, Low = percentile < 30, Medium = otherwise.
 *   Momentum:   percentile rank of |ROC(10, 5m)| over the trailing 200 closed 5m candles.
 *               Strong (Up/Down by ROC sign) = percentile > 70, Weak = otherwise.
 *
 * All inputs are CLOSED 5-minute candles only (from MarketDataStore.closed5m()), which by
 * construction excludes the currently-forming candle — satisfying the no-look-ahead rule
 * for regime classification during Minute 1 / Minute 2 of that same candle.
 *
 * No new regime dimensions or thresholds are introduced here (spec section 14).
 */
object MarketRegimeClassifier {

    private const val ADX_PERIOD = 14
    private const val ATR_PERIOD = 14
    private const val ATR_PCTL_LOOKBACK = 100
    private const val ROC_PERIOD = 10
    private const val ROC_PCTL_LOOKBACK = 200

    fun classify(closed5m: List<Candle>): MarketRegimeState? {
        val trend = classifyTrend(closed5m) ?: return null
        val volatility = classifyVolatility(closed5m) ?: return null
        val momentum = classifyMomentum(closed5m) ?: return null
        return MarketRegimeState(trend, volatility, momentum)
    }

    private fun classifyTrend(closed5m: List<Candle>): TrendRegime? {
        val adx = Indicators.adxDi(closed5m, ADX_PERIOD) ?: return null
        return when {
            adx.adx >= 25.0 && adx.plusDi > adx.minusDi -> TrendRegime.BULLISH
            adx.adx >= 25.0 && adx.minusDi > adx.plusDi -> TrendRegime.BEARISH
            else -> TrendRegime.SIDEWAYS
        }
    }

    private fun classifyVolatility(closed5m: List<Candle>): VolatilityRegime? {
        if (closed5m.size < ATR_PERIOD + 1 + ATR_PCTL_LOOKBACK) return null
        val atrSeries = Indicators.atrSeries(closed5m, ATR_PERIOD)
        val atrPct = closed5m.indices.mapNotNull { i ->
            val atr = atrSeries[i] ?: return@mapNotNull null
            if (closed5m[i].close == 0.0) null else atr / closed5m[i].close * 100.0
        }
        val pct = Indicators.percentileRank(atrPct, ATR_PCTL_LOOKBACK) ?: return null
        return when {
            pct > 70.0 -> VolatilityRegime.HIGH
            pct < 30.0 -> VolatilityRegime.LOW
            else -> VolatilityRegime.MEDIUM
        }
    }

    private fun classifyMomentum(closed5m: List<Candle>): MomentumRegime? {
        if (closed5m.size < ROC_PERIOD + 1 + ROC_PCTL_LOOKBACK) return null
        val closes = closed5m.map { it.close }
        val rocSeries = Indicators.rocSeries(closes, ROC_PERIOD).filterNotNull()
        val absRoc = rocSeries.map { abs(it) }
        val pct = Indicators.percentileRank(absRoc, ROC_PCTL_LOOKBACK) ?: return null
        val latestRoc = rocSeries.lastOrNull() ?: return null
        return when {
            pct > 70.0 && latestRoc > 0 -> MomentumRegime.STRONG_UP
            pct > 70.0 && latestRoc < 0 -> MomentumRegime.STRONG_DOWN
            else -> MomentumRegime.WEAK
        }
    }
}
