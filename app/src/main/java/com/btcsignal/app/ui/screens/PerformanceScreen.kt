package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.local.SignalEntity
import com.btcsignal.app.ui.components.GothicFilterChip
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.MetricCard
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.components.SimpleBarChart
import com.btcsignal.app.ui.components.SimpleLineChart
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabPerformanceColor
import kotlinx.coroutines.launch

private val PERIODS = listOf(1, 3, 7, 14, 30, 90)

@Composable
fun PerformanceScreen() {
    val context = LocalContext.current
    val repo = remember { AppContainer.signalRepository(context) }
    val database = remember { AppContainer.strategyDatabase(context) }
    val scope = rememberCoroutineScope()

    var selectedPeriod by remember { mutableStateOf(7) }
    var rows by remember { mutableStateOf<List<SignalEntity>>(emptyList()) }

    LaunchedEffect(selectedPeriod) {
        val since = System.currentTimeMillis() - selectedPeriod * 24L * 60 * 60 * 1000
        rows = repo.getLiveSince(since)
    }

    val closed = rows.filter { it.status == "WON" || it.status == "LOST" }
    val wins = closed.count { it.status == "WON" }
    val losses = closed.count { it.status == "LOST" }
    val totalPnl = closed.sumOf { it.pnlUsd ?: 0.0 }
    val winRate = if (closed.isNotEmpty()) wins.toDouble() / closed.size * 100.0 else 0.0
    val balance = database.financialModel.startCapitalUsd + totalPnl
    val byStrategy = closed.groupBy { it.activeStrategyId }
        .mapValues { (_, v) -> v.sumOf { it.pnlUsd ?: 0.0 } }
    val best = byStrategy.maxByOrNull { it.value }
    val worst = byStrategy.minByOrNull { it.value }

    // Chronological equity curve from starting balance.
    val equityCurve = remember(closed) {
        var running = database.financialModel.startCapitalUsd
        closed.sortedBy { it.signalTimestampMillis }.map { running += (it.pnlUsd ?: 0.0); running }
    }
    val dailyPnl = remember(closed) {
        closed.groupBy { it.signalTimestampMillis / (24L * 60 * 60 * 1000) }
            .toSortedMap()
            .map { (_, v) -> v.sumOf { it.pnlUsd ?: 0.0 } }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GothicPageHeader(title = "Performance", accent = TabPerformanceColor)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PERIODS.forEach { days ->
                GothicFilterChip(
                    selected = selectedPeriod == days,
                    onClick = { selectedPeriod = days },
                    label = "${days}d",
                    role = TabPerformanceColor
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Total Signals", "${closed.size}", Modifier.weight(1f))
            MetricCard("Win Rate", "${"%.1f".format(winRate)}%", Modifier.weight(1f), valueColor = if (winRate >= 50) GreenSignal else RedSignal)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Total PnL", "$${"%,.2f".format(totalPnl)}", Modifier.weight(1f), valueColor = if (totalPnl >= 0) GreenSignal else RedSignal)
            MetricCard("Current Balance", "$${"%,.2f".format(balance)}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Wins", "$wins", Modifier.weight(1f), valueColor = GreenSignal)
            MetricCard("Losses", "$losses", Modifier.weight(1f), valueColor = RedSignal)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                "Avg PnL / Signal", if (closed.isNotEmpty()) "$${"%.2f".format(totalPnl / closed.size)}" else "\u2014",
                Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("Equity Curve") {
            if (equityCurve.size >= 2) SimpleLineChart(equityCurve) else Text("Not enough closed signals yet.")
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("Daily PnL") {
            if (dailyPnl.isNotEmpty()) SimpleBarChart(dailyPnl) else Text("Not enough closed signals yet.")
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("Best / Worst Strategy") {
            Text("Best: ${best?.key ?: "\u2014"}  (${best?.let { "$${"%.2f".format(it.value)}" } ?: ""})", color = GreenSignal)
            Text("Worst: ${worst?.key ?: "\u2014"}  (${worst?.let { "$${"%.2f".format(it.value)}" } ?: ""})", color = RedSignal)
        }
    }
}
