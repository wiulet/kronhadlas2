package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Direction
import com.btcsignal.app.data.model.StrategyDef

data class StrategyVote(
    val strategy: StrategyDef,
    val direction: Direction,
    val score: Double
)

sealed class ConflictResolution {
    data class Decision(val winner: StrategyVote) : ConflictResolution()
    data class NoSignal(val reason: String) : ConflictResolution()
}

/**
 * Implements the conflict-resolution logic proposed in
 * BTC_5m_Strategy_Research_Report.md section 8 ("منطق پیشنهادی حل تعارض برای موتور
 * مرحله بعد"), exactly as written:
 *
 *   1. If every currently-firing strategy agrees on direction, issue that direction
 *      (attributed to whichever agreeing strategy has the highest Dynamic Score, since
 *      the canonical Signal object carries exactly one Active Strategy).
 *   2. If strategies disagree, the direction of the strategy with the highest Dynamic
 *      Score at that moment is chosen.
 *   3. If the score gap between the top two conflicting candidates is below a
 *      confidence threshold (report's stated initial proposal: 3% of normalized score),
 *      the engine issues NO SIGNAL rather than guessing.
 *
 * DOCUMENTED AMBIGUITY: the report proposes "۳ درصد امتیاز نرمال‌شده" (3% of normalized
 * score) without specifying the normalization basis. This engine uses the relative gap
 * `(top - second) / max(|top|, |second|, epsilon)` and requires it to be >= 0.03 to
 * accept the top candidate; this is the most direct literal reading of "3% of score"
 * and does not change which strategies exist or their thresholds — only how two
 * already-computed scores are compared.
 */
object ConflictResolver {

    private const val CONFLICT_CONFIDENCE_THRESHOLD = 0.03
    private const val EPSILON = 1e-9

    fun resolve(votes: List<StrategyVote>): ConflictResolution {
        if (votes.isEmpty()) return ConflictResolution.NoSignal("No strategy fired at this checkpoint")

        val directions = votes.map { it.direction }.toSet()
        if (directions.size == 1) {
            val winner = votes.maxBy { it.score }
            return ConflictResolution.Decision(winner)
        }

        // Conflict: take the best candidate per direction, then compare the top two.
        val bestPerDirection = votes.groupBy { it.direction }
            .mapValues { (_, v) -> v.maxBy { it.score } }
            .values
            .sortedByDescending { it.score }

        val top = bestPerDirection[0]
        val second = bestPerDirection[1]
        val denom = maxOf(kotlin.math.abs(top.score), kotlin.math.abs(second.score), EPSILON)
        val relativeGap = (top.score - second.score) / denom

        return if (relativeGap >= CONFLICT_CONFIDENCE_THRESHOLD) {
            ConflictResolution.Decision(top)
        } else {
            ConflictResolution.NoSignal(
                "Conflicting directions (${top.direction} score=${"%.4f".format(top.score)} vs " +
                    "${second.direction} score=${"%.4f".format(second.score)}), gap ${"%.4f".format(relativeGap)} " +
                    "below confidence threshold $CONFLICT_CONFIDENCE_THRESHOLD"
            )
        }
    }
}
