package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btcsignal.app.data.model.*
import com.btcsignal.app.data.model.Direction
import com.btcsignal.app.live.LiveEngineState
import com.btcsignal.app.ui.components.*
import com.btcsignal.app.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSignalScreen(highlightSignalId: String?) {
    val appState by LiveEngineState.appState.collectAsState()
    val phase by LiveEngineState.candlePhase.collectAsState()
    val livePrice by LiveEngineState.livePrice.collectAsState()
    val candleOpen by LiveEngineState.candleOpen.collectAsState()
    val movePct by LiveEngineState.currentMovePct.collectAsState()
    val candleOpenTime by LiveEngineState.candleOpenTimeMillis.collectAsState()
    val regime by LiveEngineState.marketRegime.collectAsState()
    val signal by LiveEngineState.currentSignal.collectAsState()

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            GothicBrandTitle("BTCUSDT")
            Spacer(Modifier.weight(1f))
            StatusBadge(appState.name.replace("_", " "), appStateColor(appState))
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                "Live Price", "$${"%,.2f".format(livePrice)}", Modifier.weight(1f),
                icon = Icons.Filled.TrendingUp
            )
            MetricCard(
                "Move from Open", "${"%+.4f".format(movePct)}%", Modifier.weight(1f),
                valueColor = if (movePct >= 0) GreenSignal else RedSignal,
                icon = if (movePct >= 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GothicIconBadge(Icons.Filled.Whatshot, AmberWarning, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                LabeledValue("Candle Open", if (candleOpen > 0) "$${"%,.2f".format(candleOpen)}" else "\u2014")
                Spacer(Modifier.weight(1f))
                CircularCountdown(
                    remainingSeconds = remainingSeconds(candleOpenTime, now),
                    totalSeconds = 300L,
                    ringColor = countdownRingColor(signal),
                    diameter = 76.dp,
                    ringStrokeWidth = 6.dp,
                    timeFontSize = 14.sp,
                    showLabel = false
                )
            }
            Spacer(Modifier.height(12.dp))
            // FlowRow instead of a SpaceBetween Row: with 4 pills of very different
            // natural widths ("Minute 1" vs "Candle Closed"), a SpaceBetween Row forced
            // the last, widest pill into whatever leftover space remained and its text
            // wrapped onto 3 lines. FlowRow lets every pill keep its own natural
            // (single-line) width and simply wraps onto a second line if the row is
            // too narrow, instead of squeezing the last one.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PhaseChip("Minute 1", phase == CandlePhase.MINUTE_1 || phase.ordinal > CandlePhase.MINUTE_1.ordinal)
                PhaseChip("Minute 2", phase == CandlePhase.MINUTE_2 || phase.ordinal > CandlePhase.MINUTE_2.ordinal)
                PhaseChip("Window Closed", phase == CandlePhase.PREDICTION_WINDOW_CLOSED || phase == CandlePhase.CANDLE_CLOSED)
                PhaseChip("Candle Closed", phase == CandlePhase.CANDLE_CLOSED)
            }
            Spacer(Modifier.height(14.dp))

            if (signal == null) {
                Text("No active signal for this candle yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val s = signal!!
                val highlighted = s.signalId == highlightSignalId
                Column {
                    DirectionPill(s.direction.name)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Active Strategy", s.activeStrategyId, Modifier.weight(1f))
                        LabeledValue("Confidence Score", "${"%.1f".format(s.confidencePct)}%", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Strategy Score", "%.3f".format(s.strategyScore), Modifier.weight(1f))
                        LabeledValue("Signal Price", "$${"%,.2f".format(s.signalPrice)}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Signal Time", SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(s.signalTimestampMillis)), Modifier.weight(1f))
                        LabeledValue("Signal Status", s.status.name, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    LabeledValue("Market Condition", "${s.marketRegime.trend.code} / ${s.marketRegime.volatility.code} / ${s.marketRegime.momentum.code}")
                    if (highlighted) {
                        Spacer(Modifier.height(10.dp))
                        StatusBadge("Opened from notification", AccentBlue)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        PriceMoveChart(
            candleOpenPrice = candleOpen,
            candleOpenTime = candleOpenTime,
            isConnected = appState !in setOf(AppState.DISCONNECTED, AppState.CONNECTING, AppState.ERROR),
            signalPoint = signal?.let {
                Offset((it.signalTimestampMillis - it.candleOpenTimeMillis).toFloat(), it.signalPrice.toFloat())
            }
        )

        regime?.let {
            Spacer(Modifier.height(12.dp))
            SectionCard("Market Regime") {
                LabeledValue("Trend", flaggedRegimeText(it.trend.code, it.trend == TrendRegime.SIDEWAYS || it.trend == TrendRegime.BULLISH))
                LabeledValue("Volatility", flaggedRegimeText(it.volatility.code, it.volatility == VolatilityRegime.LOW))
                LabeledValue("Momentum", flaggedRegimeText(it.momentum.code, it.momentum == MomentumRegime.WEAK))
            }
        }
    }
}

/** Appends the ⚜️ marker to a regime value when it's one of the flagged conditions
 * (Sideways/Bullish trend, Low volatility, Weak momentum). */
private fun flaggedRegimeText(code: String, flagged: Boolean): String =
    if (flagged) "$code ⚜️" else code

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PhaseChip(label: String, active: Boolean) {
    StatusBadge(label, if (active) GreenSignal else TextSecondary)
}

private fun remainingSeconds(candleOpenTimeMillis: Long, now: Long): Long {
    if (candleOpenTimeMillis == 0L) return 300L
    val closeTime = candleOpenTimeMillis + 5 * 60_000L
    return ((closeTime - now) / 1000).coerceIn(0, 300)
}

/** Countdown ring is neutral until a signal locks in for this candle, then it takes on
 * the signal's own direction color (spec request: ring matches the active signal). */
private fun countdownRingColor(signal: Signal?): androidx.compose.ui.graphics.Color =
    when (signal?.direction) {
        Direction.GREEN -> GreenSignal
        Direction.RED -> RedSignal
        null -> AccentBlue
    }

private fun appStateColor(state: AppState) = when (state) {
    AppState.CONNECTED, AppState.WAITING -> GreenSignal
    AppState.SIGNAL_ACTIVE, AppState.SIGNAL_LOCKED, AppState.ANALYZING -> AccentBlue
    AppState.CONNECTING, AppState.SYNCING, AppState.PREDICTION_WINDOW_CLOSED, AppState.CANDLE_CLOSED -> AmberWarning
    AppState.DISCONNECTED, AppState.ERROR -> RedSignal
}
