package com.btcsignal.app.data.model

/**
 * A single OHLCV candle for one timeframe. [openTimeMillis] is the candle's official
 * open timestamp (UTC epoch millis), matching Binance kline semantics.
 */
data class Candle(
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTimeMillis: Long,
    val isClosed: Boolean
)

enum class Direction { GREEN, RED }

enum class Timeframe(val minutes: Int, val binanceInterval: String) {
    M1(1, "1m"),
    M5(5, "5m"),
    H1(60, "1h"),
    H4(240, "4h");

    companion object {
        fun fromString(s: String): Timeframe = when (s) {
            "1m" -> M1
            "5m" -> M5
            "1h" -> H1
            "4h" -> H4
            else -> throw IllegalArgumentException("Unknown timeframe in Strategy Database: $s")
        }
    }
}

/** Which of the two permitted signal-generation checkpoints inside a 5m candle. */
/** O = candle open (prediction made with 0 minutes of the candle seen), A = after minute 1, B = after minute 2. */
enum class Checkpoint { O, A, B }

/** High level lifecycle state of the currently-forming 5-minute candle. */
enum class CandlePhase {
    MINUTE_1,
    MINUTE_2,
    PREDICTION_WINDOW_CLOSED,
    CANDLE_CLOSED
}

/** Overall application/connection state, shown in the UI per spec section 41. */
enum class AppState {
    CONNECTING, CONNECTED, DISCONNECTED, SYNCING, ANALYZING, WAITING,
    SIGNAL_ACTIVE, SIGNAL_LOCKED, PREDICTION_WINDOW_CLOSED, CANDLE_CLOSED, ERROR
}

/**
 * The three independent market-regime dimensions, exactly as defined in section 1.5
 * of the Strategy Research Report. All three are always computed; a strategy's
 * `regime_gate.code` is checked against whichever single dimension it names
 * (Trend_*, Vol_*, Mom_*) or "All" for no regime filter.
 */
data class MarketRegimeState(
    val trend: TrendRegime,
    val volatility: VolatilityRegime,
    val momentum: MomentumRegime
) {
    /** All regime codes this state currently satisfies (for matching against regime_gate.code). */
    fun activeCodes(): Set<String> = setOf(trend.code, volatility.code, momentum.code, "All")
}

enum class TrendRegime(val code: String) {
    BULLISH("Trend_Bullish"),
    BEARISH("Trend_Bearish"),
    SIDEWAYS("Trend_Sideways")
}

enum class VolatilityRegime(val code: String) {
    HIGH("Vol_High"),
    MEDIUM("Vol_Medium"),
    LOW("Vol_Low")
}

enum class MomentumRegime(val code: String) {
    STRONG_UP("Mom_StrongUp"),
    STRONG_DOWN("Mom_StrongDown"),
    WEAK("Mom_Weak")
}
