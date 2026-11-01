package com.btcsignal.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.btcsignal.app.data.model.*

/**
 * Persisted form of the canonical Signal object (spec sections 18, 30). Field-for-field
 * mirror of [Signal] plus the regime breakdown, so history survives app restarts and the
 * Performance/Strategy dashboards can query it directly without re-deriving anything.
 */
@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey val signalId: String,
    val candleId: String,
    val candleOpenTimeMillis: Long,
    val signalTimestampMillis: Long,
    val symbol: String,
    val candleOpen: Double,
    val signalPrice: Double,
    val direction: String,
    val activeStrategyId: String,
    val activeStrategyName: String,
    val regimeTrend: String,
    val regimeVolatility: String,
    val regimeMomentum: String,
    val strategyScore: Double,
    val confidencePct: Double,
    val entryMovePct: Double,
    val checkpoint: String,
    val status: String,
    val finalClose: Double?,
    val pnlUsd: Double?,
    val notified: Boolean,
    /** true for signals produced by the Backtest/Historical Replay engine, so Live and
     *  Backtest results never mix in the Performance dashboard unless explicitly asked. */
    val isBacktest: Boolean,
    /** JSON-encoded "Price Move Since Candle Open" path snapshot, captured at candle
     *  close so History can redraw the exact chart the signal fired against (see
     *  PricePathSnapshot.kt). Null for signals from before this column existed and for
     *  backtest rows, which have no live-rendered chart to snapshot. */
    val pricePathJson: String? = null
)

fun Signal.toEntity(isBacktest: Boolean): SignalEntity = SignalEntity(
    signalId = signalId,
    candleId = candleId,
    candleOpenTimeMillis = candleOpenTimeMillis,
    signalTimestampMillis = signalTimestampMillis,
    symbol = symbol,
    candleOpen = candleOpen,
    signalPrice = signalPrice,
    direction = direction.name,
    activeStrategyId = activeStrategyId,
    activeStrategyName = activeStrategyName,
    regimeTrend = marketRegime.trend.code,
    regimeVolatility = marketRegime.volatility.code,
    regimeMomentum = marketRegime.momentum.code,
    strategyScore = strategyScore,
    confidencePct = confidencePct,
    entryMovePct = entryMovePct,
    checkpoint = checkpoint.name,
    status = status.name,
    finalClose = finalClose,
    pnlUsd = pnlUsd,
    notified = notified,
    isBacktest = isBacktest
)

fun SignalEntity.toDirection(): Direction = Direction.valueOf(direction)
fun SignalEntity.toStatus(): SignalStatus = SignalStatus.valueOf(status)
fun SignalEntity.toCheckpoint(): Checkpoint = Checkpoint.valueOf(checkpoint)
