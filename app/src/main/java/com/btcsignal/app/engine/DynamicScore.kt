package com.btcsignal.app.engine

import com.btcsignal.app.data.model.StrategyDef
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rolling recent-window statistics for one strategy, used by [DynamicScore]. In the live
 * engine this is computed from this strategy's own signal history in Room over the
 * trailing 30 days; in Backtest it is computed the same way from chronological in-run
 * results only (never looking ahead). [zScoreVsBreakeven] is the same one-proportion
 * z-test used to produce the Strategy Database's own oosZScoreVsBreakeven numbers,
 * applied to this rolling window's own win/loss record instead of the one-time backtest
 * — see [DynamicScore.zScoreVsBreakeven]. Null only when [nSignals] is 0 (nothing to
 * compute it from yet).
 */
data class RecentWindowStats(
    val nSignals: Int,
    val pnlPerSignalUsd: Double,
    val zScoreVsBreakeven: Double? = null
)

/**
 * Implements the scoring formula from BTC_5m_Strategy_Research_Report.md section 10:
 *
 *   Dynamic_Score = 0.5 * max(z, 0)
 *                 + 10  * Expected_PnL_per_Signal_recent_window
 *                 + 0.3 * log(1 + N_signals_recent_window)
 *                 - Penalty_if_regime_mismatch
 *                 - Penalty_if_high_overlap_with_higher_score_strategy
 *                 - Penalty_if_statistically_unproven   (this engine's own addition, see below)
 *
 * REVISION (reduce false/low-quality signals without touching the +-0.03 entry range or
 * Checkpoint A/B timing - both stay exactly as the Strategy Database defines them; this
 * only changes how an already-fired, already-in-range strategy is scored against another
 * one when they disagree):
 *
 *   - Penalty_if_regime_mismatch stays structurally 0: ConflictResolver only ever scores
 *     strategies whose regime_gate already matches the current MarketRegimeState, so a
 *     mismatched strategy is excluded upstream rather than scored down.
 *   - Overlap penalty (Bug Report 3.3/4.2) is now ACTIVE: it uses the strategy's own
 *     already-computed max_signal_overlap_with_other_selected_strategies_pct so two
 *     heavily-overlapping strategies that agree don't get an inflated combined vote —
 *     the more redundant one is worth proportionally less in a conflict. Capped at
 *     [OVERLAP_PENALTY_MAX] so it discounts, never zeroes out, a strategy's score.
 *   - A new statistical-confidence penalty implements the Bug Report's 4.3 idea (14 of 31
 *     strategies have a z-score below the 95%-one-sided-confidence threshold of 1.645,
 *     i.e. are not statistically distinguishable from a coin flip against breakeven) as a
 *     SOFT, continuous discount rather than the report's alternative of excluding them
 *     outright. A hard cutoff would silence 45% of the roster and cut deep into genuine
 *     positive signals, which is exactly what this revision was asked not to do; a lone,
 *     uncontested strategy still fires regardless of this penalty (see ConflictResolver -
 *     this only ever changes who wins when strategies disagree, or how confidently).
 *
 * ANTI-OBSOLESCENCE (self-recalibration against the live market): the z-score, PnL, and N
 * fed into the three formula terms above are no longer either "pure backtest" or "pure
 * live" depending on whether a single live signal has resolved yet - they are a
 * credibility-weighted blend of the two (see `compute` below) that shifts smoothly from
 * the one-time Strategy Database snapshot toward this strategy's own live
 * track record as that record accumulates. This means a strategy whose edge quietly
 * decays as the live market's structure drifts away from the conditions it was
 * originally validated on will see its own blended z fall and its statistical-confidence
 * penalty rise automatically - no manual re-running of the research pipeline required -
 * while a strategy that keeps performing keeps its full score. It also fixes a real
 * source of score noise this revision found: the previous logic switched from the stable
 * backtest numbers to a RAW, unweighted average the instant a single live signal
 * resolved, so one early win or loss could swing a strategy's score far more than its
 * long OOS record ever would.
 */
object DynamicScore {

    // How many resolved live signals it takes for a strategy's LIVE recent-window numbers
    // to weigh as much as its static Strategy-Database numbers in the blend. Below this,
    // the stable backtest numbers still dominate; well above it, live performance takes
    // over almost completely. This is a tunable constant, not a value derived from the
    // Strategy Database - there is no "correct" answer the report specifies, so this is
    // this revision's own deliberately conservative choice (slow enough that a handful of
    // early live signals can't swing a score wildly, fast enough that a few months of live
    // trading meaningfully outweighs a year-old backtest).
    private const val LIVE_CREDIBILITY_HALF_POINT = 30.0

    // The same one-sided 95%-confidence z threshold the Bug Report's own statistical
    // review used to flag under-proven strategies (idea 4.3).
    private const val Z_CONFIDENCE_THRESHOLD = 1.645

    // Maximum score deduction when a strategy's (blended) z gives no statistical
    // confidence at all (z <= 0) - a soft discount, not exclusion. On the same scale as
    // term1's own 0.5 coefficient so it meaningfully affects close conflicts without
    // dominating the PnL term.
    private const val STATISTICAL_UNCERTAINTY_PENALTY_MAX = 0.5

    // Same scale/reasoning, applied to signal overlap (Bug Report 3.3/4.2) instead of
    // statistical confidence.
    private const val OVERLAP_PENALTY_MAX = 0.5

    fun compute(strategy: StrategyDef, recent: RecentWindowStats): Double {
        val staticZ = strategy.performance.oosZScoreVsBreakeven ?: 0.0
        val staticPnl = strategy.performance.oosPnlPerSignalUsd ?: 0.0
        val staticN = strategy.performance.oosSignals

        // credibility: 0.0 with no live history yet (pure static/backtest), approaching
        // 1.0 (pure live) as nSignals grows past LIVE_CREDIBILITY_HALF_POINT. Never
        // divides by zero since the denominator is always >= LIVE_CREDIBILITY_HALF_POINT.
        val credibility = recent.nSignals / (recent.nSignals + LIVE_CREDIBILITY_HALF_POINT)
        val liveZ = recent.zScoreVsBreakeven ?: staticZ
        val blendedZ = credibility * liveZ + (1 - credibility) * staticZ
        val blendedPnl = credibility * recent.pnlPerSignalUsd + (1 - credibility) * staticPnl
        val blendedN = credibility * recent.nSignals + (1 - credibility) * staticN

        val term1 = 0.5 * max(blendedZ, 0.0)
        val term2 = 10.0 * blendedPnl
        val term3 = 0.3 * ln(1.0 + blendedN)
        val regimeMismatchPenalty = 0.0 // structurally excluded upstream, see class doc
        val overlapPenalty = OVERLAP_PENALTY_MAX * (strategy.maxSignalOverlapPct / 100.0).coerceIn(0.0, 1.0)
        val statisticalUncertaintyPenalty = STATISTICAL_UNCERTAINTY_PENALTY_MAX *
            min(1.0, max(0.0, (Z_CONFIDENCE_THRESHOLD - blendedZ) / Z_CONFIDENCE_THRESHOLD))

        return term1 + term2 + term3 - regimeMismatchPenalty - overlapPenalty - statisticalUncertaintyPenalty
    }

    /**
     * One-proportion z-test of an observed win rate against the Strategy Database's fixed
     * breakeven rate - the exact same test that produced the Strategy Database's own
     * oosZScoreVsBreakeven numbers and the Bug Report's 4.3 statistical review, just
     * applied to a rolling live/backtest-replay window instead of the one-time full-history
     * backtest. Kept here as the SINGLE place this formula is implemented (Bug Report 3.2
     * flagged exactly this kind of duplication risk elsewhere) so SignalRepository (live)
     * and BacktestEngine (historical replay) can never quietly compute it two different
     * ways. Returns null when there's no data to test yet.
     */
    fun zScoreVsBreakeven(wins: Int, nSignals: Int, breakevenWinRatePct: Double): Double? {
        if (nSignals <= 0) return null
        val p0 = breakevenWinRatePct / 100.0
        val pHat = wins.toDouble() / nSignals
        val denom = sqrt(p0 * (1 - p0) / nSignals)
        if (denom == 0.0) return null
        return (pHat - p0) / denom
    }
}
