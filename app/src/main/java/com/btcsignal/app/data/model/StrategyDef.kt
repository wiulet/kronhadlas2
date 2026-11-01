package com.btcsignal.app.data.model

/**
 * One indicator/pattern component of a strategy, holding its raw parameters exactly as
 * they appear in strategies_parameters.json. `params` is intentionally a generic bag —
 * the engine looks up the fields it needs per `indicator` type (see
 * engine/ComponentEvaluator.kt) rather than this class re-declaring 27 different shapes.
 * This guarantees every field present in the source JSON is preserved verbatim and nothing
 * is silently dropped or defaulted.
 */
data class StrategyComponent(
    val indicator: String,
    val primitiveId: String?,
    val hypothesis: String?,
    val params: Map<String, Any?>   // all other raw JSON fields for this component
)

data class WalkForwardBlock(
    val block: String,
    val isOos: Boolean,
    val n: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double?,
    val pnl: Double
)

data class StrategyPerformance(
    val isSignals: Int,
    val isWinRatePct: Double,
    val oosSignals: Int,
    val oosWinRatePct: Double,
    val oosPnlPerSignalUsd: Double?,
    val oosZScoreVsBreakeven: Double?
)

/**
 * Full strategy definition, one-to-one with a `strategies[i]` entry in
 * strategies_parameters.json. Nothing here is invented: every field is read directly
 * from the Strategy Database at load time by StrategyRegistry.
 */
data class StrategyDef(
    val id: String,
    val marketConditionBucket: String,
    val regimeGateCode: String,          // e.g. "Trend_Bullish", "Vol_High", "All"
    val regimeGateDescription: String,
    val logicDescription: String,        // e.g. "AND of all components below..."
    val components: List<StrategyComponent>,
    val directionDescription: String,
    val performance: StrategyPerformance,
    val walkForwardBlocks: List<WalkForwardBlock>,
    val maxSignalOverlapPct: Double,
    val confidenceFlag: String?
)

data class HardConstraints(
    val entryRangeGreenPct: ClosedFloatingPointRange<Double>,
    val entryRangeRedPct: ClosedFloatingPointRange<Double>,
    val outcomeTieCountsAsRed: Boolean
)

data class FinancialModelSpec(
    val startCapitalUsd: Double,
    val stakePerSignalUsd: Double,
    val winUsd: Double,
    val lossUsd: Double,
    val breakevenWinRatePct: Double
)

/** The full parsed Strategy Database: single source of truth for the whole app. */
data class StrategyDatabase(
    val asset: String,
    val targetTimeframe: String,
    val hardConstraints: HardConstraints,
    val financialModel: FinancialModelSpec,
    val strategies: List<StrategyDef>
)
