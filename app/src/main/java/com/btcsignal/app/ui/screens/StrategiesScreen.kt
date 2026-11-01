package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.model.MarketRegimeState
import com.btcsignal.app.data.model.StrategyDef
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.live.LiveEngineState
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.LabeledValueInline
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.components.StatusBadge
import com.btcsignal.app.ui.theme.AmberWarning
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabStrategiesColor
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * A strategy's "Current Status" (spec sections 11, 33) is derived, not stored: a
 * strategy is ACTIVE right now if its regime_gate matches the market regime the Live
 * Engine has most recently classified (regimeGateCode == "All" or one of the three
 * active regime codes), same rule CoreSignalEngine itself uses to decide eligibility.
 * If no regime has been classified yet (engine still warming up / no live signal
 * evaluated yet), status shows as "Unknown" rather than guessing.
 */
private enum class StrategyStatus { ACTIVE, INACTIVE, UNKNOWN }

private fun statusOf(strategy: StrategyDef, regime: MarketRegimeState?): StrategyStatus {
    if (regime == null) return StrategyStatus.UNKNOWN
    val eligible = strategy.regimeGateCode == "All" || strategy.regimeGateCode in regime.activeCodes()
    return if (eligible) StrategyStatus.ACTIVE else StrategyStatus.INACTIVE
}

@Composable
fun StrategiesScreen() {
    val context = LocalContext.current
    val database = remember { AppContainer.strategyDatabase(context) }
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val scope = rememberCoroutineScope()
    val regime by LiveEngineState.marketRegime.collectAsState()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    val activeCount = database.strategies.count { statusOf(it, regime) == StrategyStatus.ACTIVE }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GothicPageHeader(
                title = "Strategy Registry",
                subtitle = "${database.strategies.size} strategies loaded from strategies_parameters.json \u2014 single source of truth for Live, Backtest, and this dashboard.",
                accent = TabStrategiesColor
            )
            if (regime == null) {
                StatusBadge("Regime not classified yet \u2014 waiting on the Live Engine", TextSecondary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge("$activeCount active now", GreenSignal)
                    StatusBadge("${database.strategies.size - activeCount} inactive now", TextSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Current regime: ${regime!!.trend.code} / ${regime!!.volatility.code} / ${regime!!.momentum.code}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        items(database.strategies, key = { it.id }) { strategy ->
            val blocked = strategy.id in settings.blockedStrategyIds
            StrategyCard(
                strategy = strategy,
                status = statusOf(strategy, regime),
                blocked = blocked,
                onToggleBlock = {
                    scope.launch { settingsRepo.setStrategyBlocked(strategy.id, !blocked) }
                }
            )
        }
    }
}

@Composable
private fun StrategyCard(
    strategy: StrategyDef,
    status: StrategyStatus,
    blocked: Boolean,
    onToggleBlock: () -> Unit
) {
    SectionCard(title = "${strategy.id} \u2014 ${strategy.marketConditionBucket}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when {
                blocked -> "BLOCKED" to AmberWarning
                status == StrategyStatus.ACTIVE -> "ACTIVE" to GreenSignal
                status == StrategyStatus.INACTIVE -> "INACTIVE" to TextSecondary
                else -> "UNKNOWN" to TextSecondary
            }
            StatusBadge(label, color)
            GothicButton(
                text = if (blocked) "Unblock" else "Block",
                onClick = onToggleBlock,
                role = if (blocked) GothicRole.GREEN else GothicRole.RED,
                filled = false,
                compact = true
            )
        }
        Spacer(Modifier.height(6.dp))
        LabeledValueInline("Regime Gate", strategy.regimeGateCode)
        LabeledValueInline("Direction Logic", strategy.directionDescription)
        LabeledValueInline("Components", strategy.components.joinToString(" AND ") { it.indicator })
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text("In-Sample", style = MaterialTheme.typography.labelSmall)
                Text(
                    "${strategy.performance.isSignals} signals \u2022 ${"%.1f".format(strategy.performance.isWinRatePct)}% WR",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column {
                Text("Out-of-Sample", style = MaterialTheme.typography.labelSmall)
                val oosColor = if (strategy.performance.oosWinRatePct >= 50.0) GreenSignal else RedSignal
                Text(
                    "${strategy.performance.oosSignals} signals \u2022 ${"%.1f".format(strategy.performance.oosWinRatePct)}% WR",
                    style = MaterialTheme.typography.bodyMedium,
                    color = oosColor
                )
            }
        }
        if (blocked) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Blocked \u2014 this strategy will not issue new live signals until unblocked.",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning
            )
        }
        strategy.confidenceFlag?.let {
            Spacer(Modifier.height(6.dp))
            Text("Flag: $it", style = MaterialTheme.typography.labelSmall, color = RedSignal)
        }
    }
}
