package com.btcsignal.app.data.repository

import com.btcsignal.app.data.local.*
import com.btcsignal.app.data.model.Signal
import com.btcsignal.app.data.model.SignalStatus
import com.btcsignal.app.engine.DynamicScore
import com.btcsignal.app.engine.RecentWindowStats
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Single persistence gateway for signals. Enforces Signal Lock (spec section 9: max one
 * signal per 5-minute candle) via an in-memory guard backed by a DB uniqueness check, so
 * the guarantee survives process death/restart as well as concurrent evaluation.
 */
class SignalRepository(private val dao: SignalDao) {

    // Fast in-memory guard against re-evaluating a candle twice within the same process
    // run; the DB check (isCandleLocked) is the authoritative, restart-safe guarantee.
    private val lockedCandlesThisSession: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    suspend fun isCandleLocked(candleId: String, isBacktest: Boolean): Boolean {
        if (!isBacktest && lockedCandlesThisSession.contains(candleId)) return true
        return dao.countForCandle(candleId, isBacktest) > 0
    }

    suspend fun saveSignal(signal: Signal, isBacktest: Boolean) {
        if (!isBacktest) lockedCandlesThisSession.add(signal.candleId)
        dao.insert(signal.toEntity(isBacktest))
    }

    suspend fun markResult(signalId: String, status: SignalStatus, finalClose: Double, pnlUsd: Double) {
        val existing = dao.getById(signalId) ?: return
        dao.update(existing.copy(status = status.name, finalClose = finalClose, pnlUsd = pnlUsd))
    }

    /** Duplicate-notification protection (spec section 23): marks notified exactly once. */
    suspend fun markNotifiedIfNeeded(signalId: String): Boolean {
        val existing = dao.getById(signalId) ?: return false
        if (existing.notified) return false
        dao.update(existing.copy(notified = true))
        return true
    }

    /** Persists the "Price Move Since Candle Open" path walked during this signal's
     *  candle, captured at candle close (LiveMonitoringService.handleCandleClosed), so
     *  History can redraw the exact chart when the entry is tapped. No-op if the signal
     *  row no longer exists (e.g. history was cleared mid-candle). */
    suspend fun savePriceSnapshot(signalId: String, pricePathJson: String) {
        val existing = dao.getById(signalId) ?: return
        dao.update(existing.copy(pricePathJson = pricePathJson))
    }

    fun observeLiveHistory(): Flow<List<SignalEntity>> = dao.observeLiveHistory()

    suspend fun getLiveSince(sinceMillis: Long): List<SignalEntity> = dao.getLiveSince(sinceMillis)

    suspend fun recentWindowStatsFor(strategyId: String, breakevenWinRatePct: Double, windowDays: Int = 30): RecentWindowStats {
        val since = System.currentTimeMillis() - windowDays * 24L * 60 * 60 * 1000
        val rows = dao.getLiveForStrategySince(strategyId, since).filter { it.status == "WON" || it.status == "LOST" }
        if (rows.isEmpty()) return RecentWindowStats(0, 0.0)
        val totalPnl = rows.sumOf { it.pnlUsd ?: 0.0 }
        val wins = rows.count { it.status == "WON" }
        val z = DynamicScore.zScoreVsBreakeven(wins, rows.size, breakevenWinRatePct)
        return RecentWindowStats(nSignals = rows.size, pnlPerSignalUsd = totalPnl / rows.size, zScoreVsBreakeven = z)
    }

    suspend fun getBacktestRange(fromMillis: Long, toMillis: Long): List<SignalEntity> =
        dao.getBacktestRange(fromMillis, toMillis)

    suspend fun clearBacktestResults() = dao.clearBacktestResults()

    /** Wipes the Live signal history (History screen "Clear" button). Does not touch
     *  backtest results and does not affect strategy/settings configuration. */
    suspend fun clearLiveHistory() = dao.clearLiveHistory()

    suspend fun getMostRecentActiveLiveSignal(): SignalEntity? = dao.getMostRecentActiveLiveSignal()
}
