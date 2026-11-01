package com.btcsignal.app.engine

import com.btcsignal.app.data.model.StrategyDef
import com.btcsignal.app.data.model.StrategyPerformance
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers this revision's rewrite of DynamicScore (Bug Report 3.3/4.2/4.3 + the
 * anti-obsolescence live/static blend). Expected totals come from an independent
 * Python re-implementation of the exact same formula, not by re-deriving them from this
 * production code.
 */
class DynamicScoreTest {

    private fun strategy(
        staticZ: Double?,
        staticPnl: Double?,
        staticN: Int,
        overlapPct: Double
    ) = StrategyDef(
        id = "TEST",
        marketConditionBucket = "test",
        regimeGateCode = "All",
        regimeGateDescription = "",
        logicDescription = "",
        components = emptyList(),
        directionDescription = "",
        performance = StrategyPerformance(0, 0.0, staticN, 0.0, staticPnl, staticZ),
        walkForwardBlocks = emptyList(),
        maxSignalOverlapPct = overlapPct,
        confidenceFlag = null
    )

    @Test
    fun `with no live history yet, score matches the static Strategy Database numbers alone`() {
        val s = strategy(staticZ = 2.0, staticPnl = 0.05, staticN = 100, overlapPct = 0.0)
        val score = DynamicScore.compute(s, RecentWindowStats(0, 0.0))
        assertEquals(2.884536, score, 0.0001)
    }

    @Test
    fun `overlap penalty (Bug 3_3 4_2) costs exactly its max at 100 percent overlap, zero at 0 percent`() {
        val low = strategy(staticZ = 1.8, staticPnl = 0.02, staticN = 50, overlapPct = 0.0)
        val high = strategy(staticZ = 1.8, staticPnl = 0.02, staticN = 50, overlapPct = 100.0)
        val scoreLow = DynamicScore.compute(low, RecentWindowStats(0, 0.0))
        val scoreHigh = DynamicScore.compute(high, RecentWindowStats(0, 0.0))
        assertEquals(2.279548, scoreLow, 0.0001)
        assertEquals(1.779548, scoreHigh, 0.0001)
        // Same 0.5-point gap as the max overlap-penalty coefficient - nothing else differs.
        assertEquals(0.5, scoreLow - scoreHigh, 0.0001)
    }

    @Test
    fun `statistical-confidence penalty (Bug 4_3) discounts a weak z-score but never zeroes the score out`() {
        // z = 0 (no statistical edge over breakeven at all) still gets a full score
        // contribution from a real PnL/N track record - it is discounted, not excluded,
        // exactly so genuinely uncontested positive signals are not thrown away.
        val weak = strategy(staticZ = 0.0, staticPnl = 0.05, staticN = 50, overlapPct = 0.0)
        val score = DynamicScore.compute(weak, RecentWindowStats(0, 0.0))
        assertEquals(1.179548, score, 0.0001)
        assertTrue("A statistically weak strategy must still score above zero, not be excluded", score > 0.0)
    }

    @Test
    fun `a strategy with a strong z-score pays no statistical-confidence penalty`() {
        val strong = strategy(staticZ = 3.0, staticPnl = 0.05, staticN = 50, overlapPct = 0.0)
        val weak = strategy(staticZ = 0.0, staticPnl = 0.05, staticN = 50, overlapPct = 0.0)
        val scoreStrong = DynamicScore.compute(strong, RecentWindowStats(0, 0.0))
        val scoreWeak = DynamicScore.compute(weak, RecentWindowStats(0, 0.0))
        // The gap must exceed what term1 alone explains (0.5*3.0 vs 0.5*0.0 = 1.5) because
        // the weak strategy also pays the statistical-uncertainty penalty the strong one doesn't.
        assertTrue(scoreStrong - scoreWeak > 1.5)
    }

    @Test
    fun `live credibility reaches exactly 0_5 at 30 resolved signals, blending static and live evenly`() {
        // staticZ=1.0, liveZ=3.0, n=30 (the tuned half-point) -> credibility=0.5 exactly,
        // so blendedZ must land exactly on the midpoint, 2.0.
        val s = strategy(staticZ = 1.0, staticPnl = 0.0, staticN = 0, overlapPct = 0.0)
        val recent = RecentWindowStats(nSignals = 30, pnlPerSignalUsd = 0.0, zScoreVsBreakeven = 3.0)
        val score = DynamicScore.compute(s, recent)
        assertEquals(1.831777, score, 0.0001)
    }

    @Test
    fun `more live signals means more live influence - anti-obsolescence direction check`() {
        // Live performance (z=3.0) is far better than the stale static snapshot (z=0.2).
        // As nSignals grows, the engine should trust the live number more and more, so the
        // score should climb monotonically toward the pure-live value.
        val s = strategy(staticZ = 0.2, staticPnl = 0.01, staticN = 200, overlapPct = 0.0)
        val scoreFewLive = DynamicScore.compute(s, RecentWindowStats(5, 0.05, 3.0))
        val scoreManyLive = DynamicScore.compute(s, RecentWindowStats(300, 0.05, 3.0))
        assertTrue(
            "More corroborating live evidence of an improved edge should score higher, not lower or flat",
            scoreManyLive > scoreFewLive
        )
    }

    @Test
    fun `more live signals means more live influence when live performance has decayed too`() {
        // Mirror image of the test above: live performance (z=-0.5) is now WORSE than the
        // stale static snapshot (z=2.5) - simulating a strategy whose edge decayed after
        // the one-time backtest. The engine should trust this bad news more as it
        // accumulates, not keep clinging to the old, rosier backtest number forever.
        val s = strategy(staticZ = 2.5, staticPnl = 0.05, staticN = 200, overlapPct = 0.0)
        val scoreFewLive = DynamicScore.compute(s, RecentWindowStats(5, -0.02, -0.5))
        val scoreManyLive = DynamicScore.compute(s, RecentWindowStats(300, -0.02, -0.5))
        assertTrue(
            "A decaying live track record should pull the score down further as it accumulates",
            scoreManyLive < scoreFewLive
        )
    }

    @Test
    fun `zScoreVsBreakeven matches the standard one-proportion z-test`() {
        // 75 percent observed win rate vs a 50 percent breakeven, n=100 -> z=5.0 exactly.
        assertEquals(5.0, DynamicScore.zScoreVsBreakeven(wins = 75, nSignals = 100, breakevenWinRatePct = 50.0)!!, 0.0001)
    }

    @Test
    fun `zScoreVsBreakeven is null with no signals to test`() {
        assertNull(DynamicScore.zScoreVsBreakeven(wins = 0, nSignals = 0, breakevenWinRatePct = 66.7))
    }
}
