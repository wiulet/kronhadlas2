package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import com.btcsignal.app.data.model.CandlePhase
import com.btcsignal.app.data.model.Checkpoint

sealed class CandleEvent {
    /** A 5-minute candle has fully closed (all 5 of its 1m sub-candles were present). */
    data class FiveMinuteCandleClosed(val candle: Candle) : CandleEvent()

    /** Checkpoint A (after minute 1) or Checkpoint B (after minute 2) has just been reached. */
    data class CheckpointReached(
        val checkpoint: Checkpoint,
        val candleOpenTimeMillis: Long,
        val candleOpen: Double,
        val referencePrice: Double,
        val minute1: Candle?,
        val minute2: Candle?,
        val timestampMillis: Long
    ) : CandleEvent()

    data class PhaseChanged(val phase: CandlePhase) : CandleEvent()
}

/**
 * Builds the currently-forming 5-minute BTCUSDT candle from a sequential stream of
 * CLOSED 1-minute candles (spec section 5), and emits Checkpoint A / Checkpoint B events
 * exactly at the moments the Strategy Database defines them (immediately after the 1st
 * and 2nd 1-minute sub-candles close — meta.hard_constraints.checkpoints). This same
 * class drives both LiveMonitoringService (fed by the Binance WebSocket) and
 * BacktestEngine / HistoricalReplay (fed by historical REST klines in chronological
 * order), which is how spec section 39's Live/Backtest consistency requirement is met
 * for candle-timing logic specifically.
 *
 * A 5-minute candle is only ever reported as closed once all 5 of its constituent
 * 1-minute candles have actually been observed — if data is missing, no
 * FiveMinuteCandleClosed event fires for that period (spec section 27, data integrity).
 */
class CandleAggregator {

    var currentPhase: CandlePhase = CandlePhase.CANDLE_CLOSED
        private set

    private var bucketStart: Long = -1L
    private var open = 0.0
    private var high = Double.NEGATIVE_INFINITY
    private var low = Double.POSITIVE_INFINITY
    private var close = 0.0
    private var volume = 0.0
    private var minuteIndex = 0
    private var minute1: Candle? = null
    private var minute2: Candle? = null

    fun onClosed1mCandle(candle: Candle): List<CandleEvent> {
        val events = ArrayList<CandleEvent>()
        val newBucketStart = (candle.openTimeMillis / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS

        when {
            bucketStart == -1L -> resetBucket(newBucketStart, candle)
            // Starting a new 5-minute bucket. The PREVIOUS bucket's close event (if it
            // actually received all 5 of its sub-candles) was already emitted the instant
            // its 5th sub-candle was processed - see the `minuteIndex == 5` case below -
            // not deferred until here. If the previous bucket never reached 5 sub-candles
            // (missing 1m data), it silently never closes, exactly matching the "no event
            // on incomplete data" contract documented above.
            newBucketStart != bucketStart -> resetBucket(newBucketStart, candle)
            else -> {
                high = maxOf(high, candle.high)
                low = minOf(low, candle.low)
                close = candle.close
                volume += candle.volume
            }
        }

        minuteIndex += 1
        when (minuteIndex) {
            1 -> {
                minute1 = candle
                currentPhase = CandlePhase.MINUTE_1
                events.add(CandleEvent.PhaseChanged(currentPhase))
                events.add(
                    CandleEvent.CheckpointReached(
                        Checkpoint.A, bucketStart, open, candle.close, minute1, null, candle.closeTimeMillis
                    )
                )
            }
            2 -> {
                minute2 = candle
                currentPhase = CandlePhase.MINUTE_2
                events.add(CandleEvent.PhaseChanged(currentPhase))
                events.add(
                    CandleEvent.CheckpointReached(
                        Checkpoint.B, bucketStart, open, candle.close, minute1, minute2, candle.closeTimeMillis
                    )
                )
            }
            3 -> {
                currentPhase = CandlePhase.PREDICTION_WINDOW_CLOSED
                events.add(CandleEvent.PhaseChanged(currentPhase))
            }
            5 -> {
                // The 5th and final 1-minute sub-candle for this bucket has just been
                // merged into open/high/low/close/volume above - the 5-minute candle is
                // complete NOW. Report it immediately instead of waiting for the first
                // candle of the NEXT bucket to arrive (Bug Report 2.1 / 3.7): that extra
                // candle of delay meant a signal issued in the very last 5m window of a
                // backtest run could never resolve, staying ACTIVE forever, and live UI /
                // DB updates always lagged the real candle close by a full minute.
                events.add(
                    CandleEvent.FiveMinuteCandleClosed(
                        Candle(bucketStart, open, high, low, close, volume, bucketStart + FIVE_MIN_MILLIS - 1, true)
                    )
                )
                // Checkpoint O: the NEXT 5m candle has just opened. Its open price is approximated by the
                // last 1m close (BTC trades continuously); no minute of the new candle has been seen yet.
                events.add(
                    CandleEvent.CheckpointReached(
                        Checkpoint.O, bucketStart + FIVE_MIN_MILLIS, candle.close, candle.close, null, null, candle.closeTimeMillis
                    )
                )
            }
        }
        return events
    }

    fun currentCandleOpenTimeMillis(): Long? = if (bucketStart == -1L) null else bucketStart
    fun currentCandleOpen(): Double? = if (bucketStart == -1L) null else open
    fun currentMinute1(): Candle? = minute1
    fun currentMinute2(): Candle? = minute2

    /** The 5-minute bucket start that [candle] belongs to, regardless of whether it has
     *  closed yet. Lets callers detect a new 5-minute candle the instant it starts
     *  forming (e.g. from live/unclosed kline ticks), instead of only finding out once
     *  its first 1-minute sub-candle has fully closed via [onClosed1mCandle]. */
    fun bucketStartFor(candle: Candle): Long = (candle.openTimeMillis / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS

    /** True once [candle] (closed or still forming) belongs to a later 5-minute bucket
     *  than the one this aggregator currently has committed via [onClosed1mCandle]. */
    fun isNewBucket(candle: Candle): Boolean =
        bucketStart != -1L && bucketStartFor(candle) != bucketStart

    private fun resetBucket(newStart: Long, candle: Candle) {
        bucketStart = newStart
        open = candle.open
        high = candle.high
        low = candle.low
        close = candle.close
        volume = candle.volume
        minuteIndex = 0
        minute1 = null
        minute2 = null
    }

    companion object {
        private const val FIVE_MIN_MILLIS = 5 * 60_000L
    }
}
