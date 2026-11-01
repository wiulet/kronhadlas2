package com.btcsignal.app.engine

import org.junit.Assert.*
import org.junit.Test

class StrategyRegistryTest {

    private fun loadRealDatabase(): String =
        StrategyRegistryTest::class.java.classLoader!!
            .getResourceAsStream("strategies_parameters.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `every strategy in the source JSON is parsed - coverage check`() {
        val json = loadRealDatabase()
        val db = StrategyRegistry.parse(json)

        val rawIds = Regex("\"id\"\\s*:\\s*\"(STRAT-[A-Z0-9]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        val parsedIds = db.strategies.map { it.id }.toSet()

        assertEquals("Strategy coverage mismatch: database vs implementation", rawIds, parsedIds)
        assertTrue("Expected at least one strategy to be parsed", db.strategies.isNotEmpty())
    }

    @Test
    fun `no strategy loses its components during parsing`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        for (strategy in db.strategies) {
            assertTrue("Strategy ${strategy.id} has no components", strategy.components.isNotEmpty())
        }
    }

    @Test
    fun `hard constraints match the exact fixed entry range from the report`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        assertEquals(0.0, db.hardConstraints.entryRangeGreenPct.start, 0.0001)
        assertEquals(0.03, db.hardConstraints.entryRangeGreenPct.endInclusive, 0.0001)
        assertEquals(-0.03, db.hardConstraints.entryRangeRedPct.start, 0.0001)
        assertEquals(0.0, db.hardConstraints.entryRangeRedPct.endInclusive, 0.0001)
    }

    @Test
    fun `financial model matches the fixed spec`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        assertEquals(100.0, db.financialModel.startCapitalUsd, 0.0001)
        assertEquals(1.0, db.financialModel.stakePerSignalUsd, 0.0001)
        assertEquals(0.5, db.financialModel.winUsd, 0.0001)
        assertEquals(-1.0, db.financialModel.lossUsd, 0.0001)
    }

    @Test
    fun `the real Strategy Database exposes the explicit outcome_tie_counts_as_red field as true`() {
        val db = StrategyRegistry.parse(loadRealDatabase())
        assertTrue(db.hardConstraints.outcomeTieCountsAsRed)
    }

    private fun minimalJson(hardConstraintsExtra: String): String = """
        {
          "meta": {
            "hard_constraints": {
              "entry_range_green_pct": [0.0, 0.03],
              "entry_range_red_pct": [-0.03, 0.0],
              $hardConstraintsExtra
            },
            "financial_model": {
              "start_capital_usd": 100.0, "stake_per_signal_usd": 1.0,
              "win_usd": 0.5, "loss_usd": -1.0, "breakeven_win_rate_pct": 66.7
            }
          },
          "strategies": []
        }
    """.trimIndent()

    @Test
    fun `Bug 1_2 - explicit outcome_tie_counts_as_red field is read directly, not derived from text`() {
        val trueJson = minimalJson("\"outcome_tie_counts_as_red\": true, \"outcome_rule\": \"unrelated text with neither keyword\"")
        assertTrue(StrategyRegistry.parse(trueJson).hardConstraints.outcomeTieCountsAsRed)

        val falseJson = minimalJson("\"outcome_tie_counts_as_red\": false, \"outcome_rule\": \"tie counted as Red, historically\"")
        // The explicit field (false) must win even though the free-text heuristic below
        // would have matched "tie" and "Red" in the same sentence and said true - this is
        // exactly Bug 1.2's negation problem: the field, not the heuristic, is authoritative.
        assertFalse(StrategyRegistry.parse(falseJson).hardConstraints.outcomeTieCountsAsRed)
    }

    @Test
    fun `Bug 1_2 - falls back to the text heuristic only when the explicit field is absent`() {
        val impliesTrue = minimalJson("\"outcome_rule\": \"tie (close==open) counted as Red\"")
        assertTrue(StrategyRegistry.parse(impliesTrue).hardConstraints.outcomeTieCountsAsRed)

        val impliesFalse = minimalJson("\"outcome_rule\": \"tie counted as Green\"")
        assertFalse(StrategyRegistry.parse(impliesFalse).hardConstraints.outcomeTieCountsAsRed)
    }
}
