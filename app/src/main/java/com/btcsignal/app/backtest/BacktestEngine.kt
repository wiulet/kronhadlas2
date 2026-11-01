package com.btcsignal.app.backtest

import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private const val FIVE_MIN_MILLIS = 5 * 60_000L

data class BacktestSummary(
    val periodDays: Int,
    val totalSignals: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val totalPnlUsd: Double,
    val finalBalanceUsd: Double,
    val strategyUsage: Map<String, Int>,
    val strategyPnl: Map<String, Double>,
    val bestStrategyId: String?
)

enum class BacktestPhase { FETCHING, REPLAYING, SAVING, DONE }

/** One resolved signal's outcome, kept chronologically per strategy during a replay so
 *  statsFor() can derive both a rolling PnL average and a rolling win/loss z-score -
 *  the same two things SignalRepository.recentWindowStatsFor derives from Room live. */
private data class RollingOutcome(val pnl: Double, val won: Boolean)

/** Reported to the UI so a long backtest (30-90 days) shows real progress instead of an
 *  indefinite spinner the person can't tell apart from a freeze. */
data class BacktestProgress(
    val phase: BacktestPhase,
    val percent: Int,      // 0-100, overall progress across the whole run() call
    val message: String
)

/**
 * Runs the SAME CoreSignalEngine as LiveMonitoringService against historical Binance
 * data (spec sections 35-39). This is not a second implementation of any strategy
 * logic — it feeds historical 1-minute klines through the identical
 * MarketDataStore -> CandleAggregator -> CoreSignalEngine pipeline used live, chronolog-
 * ically, one candle at a time, so the engine can never see data timestamped after the
 * moment it's evaluating (spec section 36).
 */
class BacktestEngine(
    private val restClient: BinanceRestClient = BinanceRestClient(),
    private val signalRepository: SignalRepository? = null
) {

    /** [warmupDays] gives the indicator engine enough closed-candle history before
     *  [periodDays] actually starts scoring, mirroring LiveMonitoringService.warmUp().
     *  [onProgress] is called from a background thread with 0-100% progress; fetching
     *  historical data is weighted 0-50%, chronological replay is weighted 50-100%. */
    suspend fun run(
        database: StrategyDatabase,
        periodDays: Int,
        warmupDays: Int = 8,
        persist: Boolean = true,
        blockedStrategyIds: Set<String> = emptySet(),
        onProgress: (BacktestProgress) -> Unit = {}
    ): BacktestSummary = withContext(Dispatchers.Default) {
        val rawNow = System.currentTimeMillis()
        // Round down to the start of the 5-minute bucket that is CURRENTLY forming as of
        // `rawNow`, then step back 1ms so the fetch/replay window covers only fully closed
        // 5-minute candles. BinanceRestClient.getKlines independently drops any candle that
        // still hasn't actually closed (e.g. if the exchange hasn't published it yet by the
        // time this call reaches it) as a second, defense-in-depth guard against the same
        // failure mode.
        val end = (rawNow / FIVE_MIN_MILLIS) * FIVE_MIN_MILLIS - 1
        val periodStart = end - periodDays * 24L * 60 * 60 * 1000
        val fetchStart = periodStart - warmupDays * 24L * 60 * 60 * 1000
        val totalRangeMinutes = ((end - fetchStart) / 60_000L).toInt().coerceAtLeast(1)

        onProgress(BacktestProgress(BacktestPhase.FETCHING, 0, "Fetching historical data from Binance\u2026"))
        val candles = restClient.getKlines("BTCUSDT", "1m", fetchStart, end) { fetchedSoFar ->
            val pct = ((fetchedSoFar.toFloat() / totalRangeMinutes) * 50f).toInt().coerceIn(0, 50)
            onProgress(
                BacktestProgress(
                    BacktestPhase.FETCHING, pct,
                    "Fetching historical data\u2026 ($fetchedSoFar / ~$totalRangeMinutes candles)"
                )
            )
        }
        onProgress(BacktestProgress(BacktestPhase.REPLAYING, 50, "Replaying ${candles.size} candles through the signal engine\u2026"))

        val store = MarketDataStore()
        val aggregator = CandleAggregator()
        val lockedCandles = HashSet<String>()
        val pendingSignals = HashMap<String, Signal>() // candleId -> active signal awaiting result
        val strategyRollingStats = HashMap<String, MutableList<RollingOutcome>>() // strategyId -> chronological outcomes, no lookahead

        val allSignals = ArrayList<Signal>()
        var wins = 0
        var losses = 0
        var totalPnl = 0.0
        val strategyUsage = HashMap<String, Int>()
        val strategyPnl = HashMap<String, Double>()

        fun statsFor(strategyId: String): RecentWindowStats {
            val history = strategyRollingStats[strategyId] ?: return RecentWindowStats(0, 0.0)
            if (history.isEmpty()) return RecentWindowStats(0, 0.0)
            val recent = history.takeLast(200) // rolling window within this replay, chronological only
            val winsInWindow = recent.count { it.won }
            val z = DynamicScore.zScoreVsBreakeven(winsInWindow, recent.size, database.financialModel.breakevenWinRatePct)
            return RecentWindowStats(recent.size, recent.map { it.pnl }.average(), z)
        }

        val totalCandles = candles.size.coerceAtLeast(1)
        var lastReportedPct = 50
        // Report roughly 200 times over the whole replay - frequent enough to feel live,
        // rare enough not to flood the UI with recompositions.
        val progressStride = (totalCandles / 200).coerceAtLeast(1)

        for ((index, candle) in candles.withIndex()) {
            store.addClosed1m(candle)
            val events = aggregator.onClosed1mCandle(candle)
            for (event in events) {
                when (event) {
                    is CandleEvent.CheckpointReached -> {
                        if (event.timestampMillis < periodStart) continue // still in warm-up window
                        val candleId = Instant.ofEpochMilli(event.candleOpenTimeMillis).toString()
                        if (candleId in lockedCandles) continue // signal lock (section 9)

                        val result = CoreSignalEngine.evaluateCheckpoint(
                            database = database,
                            store = store,
                            candleOpenTimeMillis = event.candleOpenTimeMillis,
                            candleOpen = event.candleOpen,
                            checkpoint = event.checkpoint,
                            referencePrice = event.referencePrice,
                            minute1Candle = event.minute1,
                            minute2Candle = event.minute2,
                            timestampMillis = event.timestampMillis,
                            statsProvider = ::statsFor,
                            blockedStrategyIds = blockedStrategyIds
                        )
                        val signal = result.signal ?: continue
                        lockedCandles.add(candleId)
                        pendingSignals[candleId] = signal
                        allSignals.add(signal)
                        strategyUsage[signal.activeStrategyId] = (strategyUsage[signal.activeStrategyId] ?: 0) + 1
                    }
                    is CandleEvent.FiveMinuteCandleClosed -> {
                        val candleId = Instant.ofEpochMilli(event.candle.openTimeMillis).toString()
                        val pending = pendingSignals.remove(candleId) ?: continue
                        val (status, pnl) = CoreSignalEngine.evaluateResult(database, pending.copy(candleOpen = event.candle.open), event.candle.close)
                        pending.status = status
                        pending.finalClose = event.candle.close
                        pending.pnlUsd = pnl
                        if (status == SignalStatus.WON) wins++ else losses++
                        totalPnl += pnl
                        strategyPnl[pending.activeStrategyId] = (strategyPnl[pending.activeStrategyId] ?: 0.0) + pnl
                        strategyRollingStats.getOrPut(pending.activeStrategyId) { ArrayList() }
                            .add(RollingOutcome(pnl, status == SignalStatus.WON))
                    }
                    else -> {}
                }
            }

            if (index % progressStride == 0 || index == totalCandles - 1) {
                val pct = (50 + (index.toFloat() / totalCandles * 50f).toInt()).coerceIn(50, 99)
                if (pct != lastReportedPct) {
                    lastReportedPct = pct
                    onProgress(
                        BacktestProgress(
                            BacktestPhase.REPLAYING, pct,
                            "Replaying candle ${index + 1} / $totalCandles \u2022 ${allSignals.size} signals so far"
                        )
                    )
                }
            }
        }

        if (persist && signalRepository != null) {
            onProgress(BacktestProgress(BacktestPhase.SAVING, 99, "Saving ${allSignals.size} signals\u2026"))
            signalRepository.clearBacktestResults()
            for (s in allSignals) signalRepository.saveSignal(s, isBacktest = true)
        }

        val totalSignals = wins + losses
        onProgress(BacktestProgress(BacktestPhase.DONE, 100, "Done"))
        BacktestSummary(
            periodDays = periodDays,
            totalSignals = totalSignals,
            wins = wins,
            losses = losses,
            winRatePct = if (totalSignals > 0) wins.toDouble() / totalSignals * 100.0 else 0.0,
            totalPnlUsd = totalPnl,
            finalBalanceUsd = database.financialModel.startCapitalUsd + totalPnl,
            strategyUsage = strategyUsage,
            strategyPnl = strategyPnl,
            bestStrategyId = strategyPnl.maxByOrNull { it.value }?.key
        )
    }
}
