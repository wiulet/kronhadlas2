package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Checkpoint
import com.btcsignal.app.data.model.Direction
import com.btcsignal.app.data.model.MarketRegimeState

data class StrategyTraceEntry(
    val strategyId: String,
    val regimeEligible: Boolean,
    val componentDetails: List<String>,
    val componentDirections: List<Direction?>,
    val fired: Boolean,
    val firedDirection: Direction?,
    val score: Double?
)

/**
 * One evaluated signal opportunity (spec section 40). Recorded for every checkpoint the
 * engine evaluates, whether or not a signal was ultimately produced, so a person can see
 * exactly why. Never includes credentials (there are none in this app — public market
 * data only).
 */
data class DebugTraceEntry(
    val candleId: String,
    val timestampMillis: Long,
    val checkpoint: Checkpoint,
    val currentPrice: Double,
    val movePct: Double,
    val regime: MarketRegimeState?,
    val strategyTraces: List<StrategyTraceEntry>,
    val conflictResolverResult: String,
    val signalDecision: String,
    val signalLocked: Boolean
)
