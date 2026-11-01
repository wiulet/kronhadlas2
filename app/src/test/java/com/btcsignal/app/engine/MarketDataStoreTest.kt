package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Candle
import org.junit.Assert.*
import org.junit.Test

class MarketDataStoreTest {

    private fun oneMinCandle(t: Long, open: Double, close: Double) =
        Candle(t, open, maxOf(open, close) + 1, minOf(open, close) - 1, close, 2.0, t + 59_999, true)

    @Test
    fun `5m candles are produced incrementally and match a manual aggregation`() {
        val store = MarketDataStore()
        val base = 1_700_000_000_000L / 300_000L * 300_000L

        // 12 minutes = two full 5m buckets + 2 minutes into a third (incomplete, must not appear).
        for (i in 0 until 12) {
            val t = base + i * 60_000L
            store.addClosed1m(oneMinCandle(t, 100.0 + i, 100.0 + i + 0.5))
        }

        val fiveMin = store.closed5m()
        assertEquals(2, fiveMin.size)
        assertEquals(base, fiveMin[0].openTimeMillis)
        assertEquals(base + 300_000L, fiveMin[1].openTimeMillis)
        // First bucket open = candle 0's open, close = candle 4's close (5 candles: i=0..4)
        assertEquals(100.0, fiveMin[0].open, 0.0001)
        assertEquals(104.5, fiveMin[0].close, 0.0001)
    }

    @Test
    fun `retained history is capped without breaking correctness of the most recent candles`() {
        val store = MarketDataStore(maxOneMinuteCandles = 50, max5mCandles = 5, max1hCandles = 5, max4hCandles = 5)
        val base = 1_700_000_000_000L / 300_000L * 300_000L

        // 20 full 5-minute buckets worth of 1m candles (100 minutes) - more than the 5m cap of 5.
        for (i in 0 until 100) {
            val t = base + i * 60_000L
            store.addClosed1m(oneMinCandle(t, 100.0 + i, 100.0 + i + 0.5))
        }

        val fiveMin = store.closed5m()
        assertEquals(5, fiveMin.size) // capped, not 20
        // The retained candles must be the MOST RECENT ones, in order.
        val expectedLastOpenTime = base + 19 * 300_000L
        assertEquals(expectedLastOpenTime, fiveMin.last().openTimeMillis)
        for (i in 1 until fiveMin.size) {
            assertTrue(fiveMin[i].openTimeMillis > fiveMin[i - 1].openTimeMillis)
        }
    }

    @Test
    fun `adding a large number of candles completes quickly (no quadratic full-history rescans)`() {
        val store = MarketDataStore()
        val base = 1_700_000_000_000L / 60_000L * 60_000L
        val n = 50_000 // ~34.7 days of 1-minute candles

        val elapsedMillis = kotlin.system.measureTimeMillis {
            for (i in 0 until n) {
                val t = base + i * 60_000L
                store.addClosed1m(oneMinCandle(t, 100.0, 100.1))
                // Simulate a strategy checking multiple timeframes at this point, as
                // ComponentEvaluator does at every checkpoint.
                if (i % 5 == 0) {
                    store.closed5m()
                    store.closed1h()
                    store.closed4h()
                }
            }
        }

        // With the old full-history-rescan implementation this took minutes even for
        // much smaller inputs; incremental aggregation should finish in well under a
        // few seconds on any CI/dev machine. A generous 15s bound catches any
        // regression back to O(n^2) behavior without being flaky on slow CI runners.
        assertTrue("MarketDataStore.addClosed1m + multi-timeframe reads took ${elapsedMillis}ms for $n candles - looks like a performance regression", elapsedMillis < 15_000)
    }
}
