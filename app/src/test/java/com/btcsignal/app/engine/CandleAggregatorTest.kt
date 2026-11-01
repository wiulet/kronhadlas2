package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import com.btcsignal.app.data.model.CandlePhase
import com.btcsignal.app.data.model.Checkpoint
import org.junit.Assert.*
import org.junit.Test

class CandleAggregatorTest {

    private fun oneMinCandle(openTimeMillis: Long, open: Double, close: Double) =
        Candle(openTimeMillis, open, maxOf(open, close) + 1, minOf(open, close) - 1, close, 5.0, openTimeMillis + 59_999, true)

    @Test
    fun `checkpoint A fires immediately after minute 1 closes, checkpoint B after minute 2`() {
        val aggregator = CandleAggregator()
        val base = 1_700_000_000_000L / 300_000L * 300_000L // aligned to a 5m boundary

        val eventsMinute1 = aggregator.onClosed1mCandle(oneMinCandle(base, 100.0, 100.02))
        assertTrue(eventsMinute1.any { it is CandleEvent.PhaseChanged && it.phase == CandlePhase.MINUTE_1 })
        assertTrue(eventsMinute1.any { it is CandleEvent.CheckpointReached && it.checkpoint == Checkpoint.A })

        val eventsMinute2 = aggregator.onClosed1mCandle(oneMinCandle(base + 60_000, 100.02, 100.01))
        assertTrue(eventsMinute2.any { it is CandleEvent.PhaseChanged && it.phase == CandlePhase.MINUTE_2 })
        assertTrue(eventsMinute2.any { it is CandleEvent.CheckpointReached && it.checkpoint == Checkpoint.B })

        val eventsMinute3 = aggregator.onClosed1mCandle(oneMinCandle(base + 120_000, 100.01, 100.03))
        assertTrue(eventsMinute3.any { it is CandleEvent.PhaseChanged && it.phase == CandlePhase.PREDICTION_WINDOW_CLOSED })
        assertTrue(eventsMinute3.none { it is CandleEvent.CheckpointReached })
    }

    @Test
    fun `a 5-minute candle closes immediately once its 5th sub-candle arrives, not one candle later`() {
        val aggregator = CandleAggregator()
        val base = 1_700_000_000_000L / 300_000L * 300_000L

        var eventsFromFifthCandle: List<CandleEvent> = emptyList()
        for (i in 0 until 5) {
            eventsFromFifthCandle = aggregator.onClosed1mCandle(oneMinCandle(base + i * 60_000, 100.0 + i, 100.0 + i + 0.5))
        }
        // Bug Report 2.1: the close event used to be deferred until the first candle of
        // the NEXT 5m period arrived. It must now fire on this very call - the one that
        // processes the 5th sub-candle itself - carrying the full 5-candle aggregate.
        val closed = eventsFromFifthCandle.filterIsInstance<CandleEvent.FiveMinuteCandleClosed>()
        assertEquals(1, closed.size)
        assertEquals(base, closed[0].candle.openTimeMillis)
        assertEquals(100.0, closed[0].candle.open, 0.0001)
        assertEquals(104.5, closed[0].candle.close, 0.0001)

        // And the next bucket's first candle must NOT re-emit it (no double-fire regression).
        val nextBucketEvents = aggregator.onClosed1mCandle(oneMinCandle(base + 5 * 60_000, 105.0, 105.2))
        assertTrue(nextBucketEvents.none { it is CandleEvent.FiveMinuteCandleClosed })
    }

    @Test
    fun `missing 1-minute candles prevent a 5-minute close event (data integrity)`() {
        val aggregator = CandleAggregator()
        val base = 1_700_000_000_000L / 300_000L * 300_000L

        // Only 3 of the 5 sub-candles observed, then jump straight to the next bucket.
        aggregator.onClosed1mCandle(oneMinCandle(base, 100.0, 100.1))
        aggregator.onClosed1mCandle(oneMinCandle(base + 60_000, 100.1, 100.2))
        aggregator.onClosed1mCandle(oneMinCandle(base + 120_000, 100.2, 100.3))

        val events = aggregator.onClosed1mCandle(oneMinCandle(base + 300_000, 105.0, 105.1))
        assertTrue(events.none { it is CandleEvent.FiveMinuteCandleClosed })
    }
}
