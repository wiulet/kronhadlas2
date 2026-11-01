package com.btcsignal.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.local.SignalEntity
import com.btcsignal.app.data.model.decodePricePath
import com.btcsignal.app.ui.components.GothicAlertDialog
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicDialogAction
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.PriceMoveSnapshotChart
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.AmberWarning
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.MetalHighlight
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TabHistoryColor
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(highlightSignalId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AppContainer.signalRepository(context) }
    val history by repo.observeLiveHistory().collectAsState(initial = emptyList())
    var showClearConfirm by remember { mutableStateOf(false) }
    var snapshotEntity by remember { mutableStateOf<SignalEntity?>(null) }

    snapshotEntity?.let { entity ->
        SignalSnapshotDialog(entity, onDismiss = { snapshotEntity = null })
    }

    if (showClearConfirm) {
        GothicAlertDialog(
            onDismissRequest = { showClearConfirm = false },
            accent = RedSignal,
            title = { Text("Clear history?") },
            text = { Text("This deletes all live signal history. This can't be undone.") },
            confirmButton = {
                GothicDialogAction("Yes", role = GothicRole.RED, onClick = {
                    showClearConfirm = false
                    scope.launch { repo.clearLiveHistory() }
                })
            },
            dismissButton = {
                GothicDialogAction("No", role = GothicRole.CYAN, onClick = { showClearConfirm = false })
            }
        )
    }

    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No live signals yet.", color = TextSecondary)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            GothicPageHeader(
                title = "Signal History",
                accent = TabHistoryColor,
                trailing = {
                    GothicButton(
                        "Clear",
                        onClick = { showClearConfirm = true },
                        role = GothicRole.RED,
                        filled = false,
                        compact = true
                    )
                }
            )
        }
        items(history, key = { it.signalId }) { entity ->
            HistoryRow(
                entity,
                highlighted = entity.signalId == highlightSignalId,
                onClick = { snapshotEntity = entity }
            )
        }
    }
}

@Composable
private fun HistoryRow(entity: SignalEntity, highlighted: Boolean, onClick: () -> Unit) {
    val directionColor = if (entity.direction == "GREEN") GreenSignal else RedSignal
    val statusColor = when (entity.status) {
        "WON" -> GreenSignal
        "LOST" -> RedSignal
        else -> TextSecondary
    }
    SectionCard(title = "", modifier = Modifier.clickable(onClick = onClick), glow = statusColor.takeIf { entity.status == "WON" || entity.status == "LOST" } ?: MetalHighlight) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(entity.direction, color = directionColor, style = MaterialTheme.typography.titleMedium)
                Text(entity.activeStrategyId, style = MaterialTheme.typography.bodyMedium)
                Text(
                    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date(entity.signalTimestampMillis)) + " UTC",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(entity.status, color = statusColor, style = MaterialTheme.typography.titleMedium)
                entity.pnlUsd?.let {
                    Text("${if (it >= 0) "+" else ""}$${"%.2f".format(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("${"%.1f".format(entity.confidencePct)}% conf.", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (highlighted) {
            Spacer(Modifier.height(6.dp))
            Text("\u2190 Opened from notification", style = MaterialTheme.typography.labelSmall)
        }
        if (entity.pricePathJson != null) {
            Spacer(Modifier.height(6.dp))
            Text("Tap to view chart snapshot", color = AmberWarning, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SignalSnapshotDialog(entity: SignalEntity, onDismiss: () -> Unit) {
    val directionColor = if (entity.direction == "GREEN") GreenSignal else RedSignal
    GothicAlertDialog(
        onDismissRequest = onDismiss,
        accent = directionColor,
        confirmButton = {
            GothicDialogAction("Close", role = GothicRole.CYAN, onClick = onDismiss)
        },
        title = {
            Column {
                Text(entity.activeStrategyId, style = MaterialTheme.typography.titleMedium)
                Text(
                    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date(entity.signalTimestampMillis)) + " UTC",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        text = {
            val path = remember(entity.signalId) { decodePricePath(entity.pricePathJson) }
            Column {
                Text(
                    "${entity.direction} \u2022 ${entity.status}",
                    color = directionColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (path.isEmpty()) {
                    Text(
                        "No chart snapshot was captured for this signal (the app likely wasn't running when this candle closed).",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    PriceMoveSnapshotChart(
                        pricePath = path,
                        candleOpenPrice = entity.candleOpen,
                        signalPoint = Offset(
                            (entity.signalTimestampMillis - entity.candleOpenTimeMillis).toFloat(),
                            entity.signalPrice.toFloat()
                        )
                    )
                }
            }
        }
    )
}
