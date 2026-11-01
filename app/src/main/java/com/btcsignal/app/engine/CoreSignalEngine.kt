package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class EngineResult(
    val signal: Signal?,
    val trace: DebugTraceEntry
)

/**
 * Signal entry point shared by LiveMonitoringService and BacktestEngine. All previous hadlas
 * strategies / regime / conflict-resolver logic was removed: the decision now comes ONLY from
 * the Kronos model (see kronos/KronosEngine.kt). Parameters unused by Kronos are kept so the
 * live and backtest call sites stay unchanged.
 */
object CoreSignalEngine {

    @Suppress("UNUSED_PARAMETER")
    fun evaluateCheckpoint(
        database: StrategyDatabase,
        store: MarketDataStore,
        candleOpenTimeMillis: Long,
        candleOpen: Double,
        checkpoint: Checkpoint,
        referencePrice: Double,
        minute1Candle: Candle?,
        minute2Candle: Candle?,
        timestampMillis: Long,
        statsProvider: (String) -> RecentWindowStats,
        blockedStrategyIds: Set<String> = emptySet()
    ): EngineResult = com.btcsignal.app.kronos.KronosEngine.evaluate(
        store, candleOpenTimeMillis, candleOpen, checkpoint, referencePrice, timestampMillis
    )

    /** Result determination per spec section 28: compare final close to candle open; tie counts as Red. */
    fun evaluateResult(database: StrategyDatabase, signal: Signal, finalClose: Double): Pair<SignalStatus, Double> {
        val actualDirection = when {
            finalClose > signal.candleOpen -> Direction.GREEN
            finalClose < signal.candleOpen -> Direction.RED
            else -> if (database.hardConstraints.outcomeTieCountsAsRed) Direction.RED else Direction.GREEN
        }
        val won = actualDirection == signal.direction
        val pnl = if (won) database.financialModel.winUsd else database.financialModel.lossUsd
        val status = if (won) SignalStatus.WON else SignalStatus.LOST
        return status to pnl
    }
}
