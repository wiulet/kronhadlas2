package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Direction
import com.btcsignal.app.data.model.StrategyDef
import com.btcsignal.app.data.model.StrategyPerformance
import org.junit.Assert.*
import org.junit.Test

class ConflictResolverTest {

    private fun fakeStrategy(id: String) = StrategyDef(
        id = id,
        marketConditionBucket = "test",
        regimeGateCode = "All",
        regimeGateDescription = "",
        logicDescription = "",
        components = emptyList(),
        directionDescription = "",
        performance = StrategyPerformance(0, 0.0, 0, 0.0, null, null),
        walkForwardBlocks = emptyList(),
        maxSignalOverlapPct = 0.0,
        confidenceFlag = null
    )

    @Test
    fun `no votes means no signal`() {
        val result = ConflictResolver.resolve(emptyList())
        assertTrue(result is ConflictResolution.NoSignal)
    }

    @Test
    fun `unanimous direction picks the highest-score agreeing strategy`() {
        val votes = listOf(
            StrategyVote(fakeStrategy("A"), Direction.GREEN, score = 1.0),
            StrategyVote(fakeStrategy("B"), Direction.GREEN, score = 5.0),
            StrategyVote(fakeStrategy("C"), Direction.GREEN, score = 2.0)
        )
        val result = ConflictResolver.resolve(votes)
        assertTrue(result is ConflictResolution.Decision)
        val decision = result as ConflictResolution.Decision
        assertEquals("B", decision.winner.strategy.id)
        assertEquals(Direction.GREEN, decision.winner.direction)
    }

    @Test
    fun `conflicting directions with a clear score gap picks the higher score`() {
        val votes = listOf(
            StrategyVote(fakeStrategy("A"), Direction.GREEN, score = 10.0),
            StrategyVote(fakeStrategy("B"), Direction.RED, score = 1.0)
        )
        val result = ConflictResolver.resolve(votes)
        assertTrue(result is ConflictResolution.Decision)
        assertEquals("A", (result as ConflictResolution.Decision).winner.strategy.id)
    }

    @Test
    fun `conflicting directions with scores too close together produce no signal`() {
        val votes = listOf(
            StrategyVote(fakeStrategy("A"), Direction.GREEN, score = 1.00),
            StrategyVote(fakeStrategy("B"), Direction.RED, score = 1.01)
        )
        val result = ConflictResolver.resolve(votes)
        assertTrue(result is ConflictResolution.NoSignal)
    }
}
