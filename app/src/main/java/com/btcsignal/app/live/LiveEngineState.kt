package com.btcsignal.app.live

import androidx.compose.ui.geometry.Offset
import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.DebugTraceEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton, UI-observable snapshot of the live engine (spec section 19, Live Signal
 * Panel). LiveMonitoringService is the only writer; ui/screens/LiveSignalScreen.kt is
 * the primary reader. Kept separate from the Service itself so the Compose UI can
 * observe engine state regardless of Activity/Service lifecycle timing.
 */
object LiveEngineState {
    val appState = MutableStateFlow(AppState.WAITING)
    val candlePhase = MutableStateFlow(CandlePhase.CANDLE_CLOSED)
    val livePrice = MutableStateFlow(0.0)
    val candleOpen = MutableStateFlow(0.0)
    val currentMovePct = MutableStateFlow(0.0)
    val candleOpenTimeMillis = MutableStateFlow(0L)
    val marketRegime = MutableStateFlow<MarketRegimeState?>(null)
    val currentSignal = MutableStateFlow<Signal?>(null)
    val debugTraceLog = MutableStateFlow<List<DebugTraceEntry>>(emptyList())

    /** Every live-price tick observed during the CURRENT candle (elapsed ms since candle
     *  open -> price), logged directly by LiveMonitoringService.onKlineUpdate regardless
     *  of which screen is on-screen or whether the app is foregrounded. [PriceMoveChart]
     *  (the live view) polls and copies this into its own Compose-observable state
     *  instead of walking its own composable-local list, so switching bottom-nav tabs or
     *  minimizing the app can never leave it out of sync -- there's a single continuously
     *  recorded path, not two independently-tracked ones that can drift apart. Read once
     *  more at candle close to freeze the signal's chart for History (see
     *  [lastClosedCandlePriceLog] below and PricePathSnapshot.kt); always access under
     *  `synchronized(candlePriceLog)` since writes can come from the WebSocket callback
     *  thread while a read is building a snapshot. */
    val candlePriceLog: MutableList<Offset> = ArrayList()

    /** Frozen copy of [candlePriceLog] for the candle that just finished, captured the
     *  instant the next candle's first (still-forming) tick arrives -- i.e. BEFORE
     *  [candlePriceLog] gets cleared and refilled for that new candle (see
     *  LiveMonitoringService.onKlineUpdate). Since the CandleAggregator fix (Bug Report
     *  2.1), the FiveMinuteCandleClosed event for the candle that just ended reaches
     *  handleCandleClosed immediately, at the real close -- but Binance sends the
     *  "closing" message for that candle and the "opening" message for the next one as
     *  two separate WebSocket frames, and their exact arrival order isn't guaranteed.
     *  If the new candle's tick is processed first, [candlePriceLog] has already moved
     *  on by the time handleCandleClosed runs, so reading it directly there could
     *  silently snapshot the wrong candle's path onto History. handleCandleClosed reads
     *  from here instead, and falls back to whatever is currently in [candlePriceLog]
     *  only if this doesn't match the candle being closed (both the "closing message
     *  arrived first" case, where nothing has touched [candlePriceLog] yet either, and a
     *  gap-filled/resynced candle that was never observed live end up correct this way).
     *  Always access under `synchronized(candlePriceLog)` alongside the field above. */
    var lastClosedCandlePriceLog: List<Offset> = emptyList()
    var lastClosedCandleOpenTime: Long? = null

    private const val MAX_TRACE_ENTRIES = 200

    fun pushTrace(entry: DebugTraceEntry) {
        val updated = (debugTraceLog.value + entry).takeLast(MAX_TRACE_ENTRIES)
        debugTraceLog.value = updated
    }

    /** Clears the previous candle's signal. Does NOT touch [candlePhase] -- callers
     *  (currently only the MINUTE_1 transition in LiveMonitoringService) are
     *  responsible for setting the phase themselves; overwriting it here used to
     *  immediately stomp a just-set MINUTE_1 back to CANDLE_CLOSED, which made every
     *  phase chip in the UI render as "active" the instant the new candle began. */
    fun reset() {
        currentSignal.value = null
    }
}
