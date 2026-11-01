package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.indicators.Indicators

data class ComponentResult(
    val direction: Direction?,
    val detail: String
)

/**
 * Context available at a signal-evaluation checkpoint. Every candle collection here
 * contains CLOSED candles only (see MarketDataStore) plus, separately, the two
 * 1-minute sub-candles of the currently-forming 5m candle that have closed so far
 * (minute1Candle always by Checkpoint A, minute2Candle only by Checkpoint B) — this is
 * exactly the information the Strategy Database's own "Checkpoint A / Checkpoint B"
 * definition (meta.hard_constraints.checkpoints) says is available at each point.
 */
data class EvalContext(
    val store: MarketDataStore,
    val candleOpen: Double,
    val referencePrice: Double,
    val checkpoint: Checkpoint,
    val minute1Candle: Candle?,
    val minute2Candle: Candle?
)

/**
 * Evaluates a single [StrategyComponent] and returns the Direction it votes for (or null
 * if the component's condition isn't met / required data isn't available yet). Every rule
 * implemented here is transcribed directly from the per-strategy "منطق" (logic) text in
 * BTC_5m_Strategy_Research_Report.md section 7, cross-checked against the matching
 * `primitive_id` in strategies_parameters.json. Thresholds not exposed as JSON fields
 * (Bollinger %B 0.05/0.95, Williams %R -80/-20) are fixed constants of the primitive
 * itself — they are identical across every strategy that uses that primitive_id in the
 * source report, so hardcoding them here does not alter any strategy's behavior.
 */
object ComponentEvaluator {

    private const val BB_LOW = 0.05
    private const val BB_HIGH = 0.95
    private const val WILLR_LOW = -80.0
    private const val WILLR_HIGH = -20.0

    fun evaluate(component: StrategyComponent, ctx: EvalContext): ComponentResult {
        return when (component.indicator) {
            "micro_momentum" -> evalMicroMomentum(component, ctx)
            "RSI" -> evalRsi(component, ctx)
            "Bollinger_%B" -> evalBollinger(component, ctx)
            "Stochastic %K" -> evalStochastic(component, ctx)
            "OBV_slope" -> evalObvSlope(component, ctx)
            "EMA_alignment" -> evalEmaAlignment(component, ctx)
            "Williams %R" -> evalWilliamsR(component, ctx)
            "ADX_DI_direction" -> evalAdxDiDirection(component, ctx)
            "CCI" -> evalCci(component, ctx)
            "MACD_cross" -> evalMacdCross(component, ctx)
            "HTF_trend_filter" -> evalHtfTrendFilter(component, ctx)
            "candle_pattern" -> evalCandlePattern(component, ctx)
            "micro_two_tick_confirm" -> evalTwoTickConfirm(ctx)
            "Momentum_Ratio_V2" -> evalMomentumRatioV2(component, ctx)
            else -> ComponentResult(null, "Unknown indicator '${component.indicator}' - no evaluator implemented, treated as non-firing")
        }
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun candlesFor(ctx: EvalContext, timeframe: String): List<Candle> {
        val tf = Timeframe.fromString(timeframe)
        return ctx.store.bySymbolTimeframe(tf.minutes)
    }

    // micro_momentum: move from candle open to the checkpoint reference price.
    private fun evalMicroMomentum(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val minMovePct = num(c.params["min_move_pct"]) ?: return ComponentResult(null, "missing min_move_pct")
        if (ctx.candleOpen == 0.0) return ComponentResult(null, "invalid open")
        val movePct = (ctx.referencePrice - ctx.candleOpen) / ctx.candleOpen * 100.0
        val dir = when {
            movePct > minMovePct -> Direction.GREEN
            movePct < -minMovePct -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "move=${"%.4f".format(movePct)}%% vs min=${minMovePct}%%")
    }

    private fun evalRsi(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val period = (num(c.params["period"]) ?: return ComponentResult(null, "missing period")).toInt()
        val lowTh = num(c.params["low_th"]) ?: return ComponentResult(null, "missing low_th")
        val highTh = num(c.params["high_th"]) ?: return ComponentResult(null, "missing high_th")
        val candles = candlesFor(ctx, timeframe)
        val closes = closesForRsi(ctx, timeframe, candles)
        val rsi = Indicators.rsi(closes, period) ?: return ComponentResult(null, "insufficient data for RSI($period,$timeframe)")
        val dir = when (c.hypothesis) {
            "mean_reversion" -> when {
                rsi < lowTh -> Direction.GREEN
                rsi > highTh -> Direction.RED
                else -> null
            }
            "momentum_continuation" -> when {
                rsi > highTh -> Direction.GREEN
                rsi < lowTh -> Direction.RED
                else -> null
            }
            else -> null
        }
        return ComponentResult(dir, "RSI($period,$timeframe)=${"%.2f".format(rsi)} hyp=${c.hypothesis}")
    }

    /**
     * RSI on the 1m timeframe is a special case: at a live checkpoint the "current" 1m
     * closed series must include the just-closed sub-candle of the candle being
     * evaluated (minute1Candle / minute2Candle), which MarketDataStore does not yet
     * contain until LiveMonitoringService appends it there at candle close. We splice it
     * in for the purpose of this calculation only, without ever writing it to the shared
     * store early (which would risk other consumers seeing an in-progress candle).
     */
    private fun closesForRsi(ctx: EvalContext, timeframe: String, storeCandles: List<Candle>): List<Double> {
        if (timeframe != "1m") return storeCandles.map { it.close }
        val extra = listOfNotNull(ctx.minute1Candle, ctx.minute2Candle)
        return (storeCandles + extra).map { it.close }
    }

    private fun evalBollinger(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val period = (num(c.params["period"]) ?: return ComponentResult(null, "missing period")).toInt()
        val std = num(c.params["std"]) ?: return ComponentResult(null, "missing std")
        val closes = candlesFor(ctx, timeframe).map { it.close }
        val pctB = Indicators.bollingerPercentB(closes, period, std)
            ?: return ComponentResult(null, "insufficient data for Bollinger%B($period,$timeframe)")
        val dir = when (c.hypothesis) {
            // Bollinger_%B isn't named in Bug Report 2.2, but evalStochastic/evalWilliamsR's
            // exact same structural gap (a bounded oscillator whose direction never
            // consulted c.hypothesis) applied here too. Every Bollinger_%B entry in today's
            // JSON is mean_reversion, so this branch preserves current behavior exactly;
            // it only closes off the same future silent-inversion risk described in 2.2.
            "mean_reversion" -> when {
                pctB < BB_LOW -> Direction.GREEN
                pctB > BB_HIGH -> Direction.RED
                else -> null
            }
            "momentum_continuation" -> when {
                pctB > BB_HIGH -> Direction.GREEN
                pctB < BB_LOW -> Direction.RED
                else -> null
            }
            else -> null
        }
        return ComponentResult(dir, "%%B($period,$timeframe)=${"%.3f".format(pctB)} hyp=${c.hypothesis}")
    }

    private fun evalStochastic(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val kPeriod = (num(c.params["k_period"]) ?: return ComponentResult(null, "missing k_period")).toInt()
        val dPeriod = (num(c.params["d_period"]) ?: return ComponentResult(null, "missing d_period")).toInt()
        val lowTh = num(c.params["low_th"]) ?: return ComponentResult(null, "missing low_th")
        val highTh = num(c.params["high_th"]) ?: return ComponentResult(null, "missing high_th")
        val candles = candlesFor(ctx, timeframe)
        val stoch = Indicators.stochastic(candles, kPeriod, dPeriod)
            ?: return ComponentResult(null, "insufficient data for Stochastic($kPeriod,$dPeriod,$timeframe)")
        val dir = when (c.hypothesis) {
            "mean_reversion" -> when {
                stoch.first < lowTh -> Direction.GREEN
                stoch.first > highTh -> Direction.RED
                else -> null
            }
            "momentum_continuation" -> when {
                stoch.first > highTh -> Direction.GREEN
                stoch.first < lowTh -> Direction.RED
                else -> null
            }
            else -> null
        }
        return ComponentResult(dir, "%%K($timeframe)=${"%.2f".format(stoch.first)} hyp=${c.hypothesis}")
    }

    private fun evalObvSlope(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val lookback = (num(c.params["lookback"]) ?: return ComponentResult(null, "missing lookback")).toInt()
        val candles = candlesFor(ctx, timeframe)
        val slope = Indicators.obvSlope(candles, lookback) ?: return ComponentResult(null, "insufficient data for OBV_slope")
        val dir = when {
            slope > 0 -> Direction.GREEN
            slope < 0 -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "OBV_slope($lookback,$timeframe)=${"%.4f".format(slope)}")
    }

    private fun evalEmaAlignment(c: StrategyComponent, ctx: EvalContext): ComponentResult = evalEmaCompare(c, ctx, "EMA_alignment")

    private fun evalHtfTrendFilter(c: StrategyComponent, ctx: EvalContext): ComponentResult = evalEmaCompare(c, ctx, "HTF_trend_filter")

    private fun evalEmaCompare(c: StrategyComponent, ctx: EvalContext, label: String): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val fast = (num(c.params["fast"]) ?: return ComponentResult(null, "missing fast")).toInt()
        val slow = (num(c.params["slow"]) ?: return ComponentResult(null, "missing slow")).toInt()
        val closes = candlesFor(ctx, timeframe).map { it.close }
        val emaFast = Indicators.ema(closes, fast) ?: return ComponentResult(null, "insufficient data for EMA($fast,$timeframe)")
        val emaSlow = Indicators.ema(closes, slow) ?: return ComponentResult(null, "insufficient data for EMA($slow,$timeframe)")
        val dir = when {
            emaFast > emaSlow -> Direction.GREEN
            emaFast < emaSlow -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "$label EMA$fast=${"%.2f".format(emaFast)} EMA$slow=${"%.2f".format(emaSlow)} ($timeframe)")
    }

    private fun evalWilliamsR(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val period = (num(c.params["period"]) ?: return ComponentResult(null, "missing period")).toInt()
        val candles = candlesFor(ctx, timeframe)
        val wr = Indicators.williamsR(candles, period) ?: return ComponentResult(null, "insufficient data for Williams%R($period,$timeframe)")
        val dir = when (c.hypothesis) {
            "mean_reversion" -> when {
                wr < WILLR_LOW -> Direction.GREEN
                wr > WILLR_HIGH -> Direction.RED
                else -> null
            }
            "momentum_continuation" -> when {
                wr > WILLR_HIGH -> Direction.GREEN
                wr < WILLR_LOW -> Direction.RED
                else -> null
            }
            else -> null
        }
        return ComponentResult(dir, "WilliamsR($period,$timeframe)=${"%.2f".format(wr)} hyp=${c.hypothesis}")
    }

    private fun evalAdxDiDirection(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val period = (num(c.params["period"]) ?: return ComponentResult(null, "missing period")).toInt()
        val adxThreshold = num(c.params["adx_threshold"]) ?: return ComponentResult(null, "missing adx_threshold")
        val candles = candlesFor(ctx, timeframe)
        val adx = Indicators.adxDi(candles, period) ?: return ComponentResult(null, "insufficient data for ADX($period,$timeframe)")
        val dir = when {
            adx.adx >= adxThreshold && adx.plusDi > adx.minusDi -> Direction.GREEN
            adx.adx >= adxThreshold && adx.minusDi > adx.plusDi -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "ADX($period,$timeframe)=${"%.2f".format(adx.adx)} +DI=${"%.2f".format(adx.plusDi)} -DI=${"%.2f".format(adx.minusDi)}")
    }

    private fun evalCci(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val period = (num(c.params["period"]) ?: return ComponentResult(null, "missing period")).toInt()
        val threshold = num(c.params["threshold"]) ?: return ComponentResult(null, "missing threshold")
        val candles = candlesFor(ctx, timeframe)
        val cci = Indicators.cci(candles, period) ?: return ComponentResult(null, "insufficient data for CCI($period,$timeframe)")
        val dir = when (c.hypothesis) {
            "mean_reversion" -> when {
                cci < -threshold -> Direction.GREEN
                cci > threshold -> Direction.RED
                else -> null
            }
            "momentum_continuation" -> when {
                cci > threshold -> Direction.GREEN
                cci < -threshold -> Direction.RED
                else -> null
            }
            else -> null
        }
        return ComponentResult(dir, "CCI($period,$timeframe)=${"%.2f".format(cci)} hyp=${c.hypothesis}")
    }

    private fun evalMacdCross(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val fast = (num(c.params["fast"]) ?: return ComponentResult(null, "missing fast")).toInt()
        val slow = (num(c.params["slow"]) ?: return ComponentResult(null, "missing slow")).toInt()
        val signal = (num(c.params["signal"]) ?: return ComponentResult(null, "missing signal")).toInt()
        val closes = candlesFor(ctx, timeframe).map { it.close }
        val macd = Indicators.macd(closes, fast, slow, signal) ?: return ComponentResult(null, "insufficient data for MACD($fast,$slow,$signal,$timeframe)")
        val dir = when {
            macd.macdLine > macd.signalLine -> Direction.GREEN
            macd.macdLine < macd.signalLine -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "MACD($timeframe) line=${"%.4f".format(macd.macdLine)} signal=${"%.4f".format(macd.signalLine)}")
    }

    private fun evalCandlePattern(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val timeframe = c.params["timeframe"] as? String ?: return ComponentResult(null, "missing timeframe")
        val pattern = c.params["pattern"] as? String ?: return ComponentResult(null, "missing pattern")
        val candles = candlesFor(ctx, timeframe)
        val last = candles.lastOrNull() ?: return ComponentResult(null, "no closed candle available for pattern check")
        val dir = when (pattern) {
            "marubozu" -> when (Indicators.marubozuDirection(last)) {
                1 -> Direction.GREEN
                -1 -> Direction.RED
                else -> null
            }
            else -> null
        }
        return ComponentResult(dir, "pattern=$pattern($timeframe) on candle@${last.openTimeMillis}")
    }

    private fun evalTwoTickConfirm(ctx: EvalContext): ComponentResult {
        if (ctx.checkpoint != Checkpoint.B) {
            return ComponentResult(null, "micro_two_tick_confirm only evaluable at Checkpoint B")
        }
        val m1 = ctx.minute1Candle ?: return ComponentResult(null, "minute 1 candle not available")
        val m2 = ctx.minute2Candle ?: return ComponentResult(null, "minute 2 candle not available")
        val tick1 = m1.close - m1.open
        val tick2 = m2.close - m2.open
        val dir = when {
            tick1 > 0 && tick2 > 0 -> Direction.GREEN
            tick1 < 0 && tick2 < 0 -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "tick1=${"%.2f".format(tick1)} tick2=${"%.2f".format(tick2)}")
    }

    /**
     * Momentum Ratio V2 (audit-doc "01_ANDROID_INTEGRATION_PROMPT" section 19). Available
     * from Checkpoint A onward - it only ever reads minute1Candle, so its value is
     * identical at Checkpoint A and Checkpoint B (minute2 never enters this definition).
     * Always evaluated against the causal closed-5m-candle history (candlesFor("5m")),
     * regardless of the strategy's own target timeframe, because the ATR(3) normalizer in
     * the NEXA V2 spec is defined on the target (5m) timeframe.
     */
    private fun evalMomentumRatioV2(c: StrategyComponent, ctx: EvalContext): ComponentResult {
        val threshold = num(c.params["threshold"]) ?: return ComponentResult(null, "missing threshold")
        val m1 = ctx.minute1Candle ?: return ComponentResult(null, "minute 1 candle not available")
        val candles5m = candlesFor(ctx, "5m")
        val ratio = Indicators.momentumRatioV2(candles5m, ctx.candleOpen, m1.high, m1.low)
            ?: return ComponentResult(null, "insufficient data for Momentum_Ratio_V2 (ATR(3,5m) unavailable)")
        val dir = when {
            ratio.longRatio > threshold && ratio.longRatio > ratio.shortRatio -> Direction.GREEN
            ratio.shortRatio > threshold && ratio.shortRatio > ratio.longRatio -> Direction.RED
            else -> null
        }
        return ComponentResult(dir, "MomentumRatioV2 long=${"%.3f".format(ratio.longRatio)} short=${"%.3f".format(ratio.shortRatio)} th=$threshold")
    }
}
