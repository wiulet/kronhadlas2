package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.kronos.KronosModelManager
import com.btcsignal.app.kronos.KronosModelState
import com.btcsignal.app.kronos.KronosPrefs
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.TabStrategiesColor
import kotlinx.coroutines.launch

/** Replaces the old Strategies screen: Kronos is now the only signal engine. One tap downloads and activates it. */
@Composable
fun KronosScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by KronosModelManager.state.collectAsState()
    var url by remember { mutableStateOf(KronosPrefs.modelUrl(context)) }
    var threshold by remember { mutableFloatStateOf(KronosPrefs.threshold(context)) }
    var thresholdText by remember { mutableStateOf("%.1f".format(KronosPrefs.threshold(context) * 100f).removeSuffix(".0")) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GothicPageHeader(title = "Kronos", accent = TabStrategiesColor)

        SectionCard("Kronos AI model") {
            when (val s = state) {
                is KronosModelState.NotInstalled -> Text("Not installed. Tap Download - the model is fetched, verified and activated automatically.")
                is KronosModelState.Downloading -> {
                    val f = s.fraction
                    Text(if (f >= 0) "Downloading ${(f * 100).toInt()}%  (${s.doneBytes / 1_000_000} / ${s.totalBytes / 1_000_000} MB)"
                         else "Downloading ${s.doneBytes / 1_000_000} MB ...")
                    Spacer(Modifier.height(8.dp))
                    if (f >= 0) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is KronosModelState.Verifying -> {
                    Text("Verifying and installing ...")
                    Spacer(Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is KronosModelState.Installed -> Text("Installed and active: ${s.name} (%.1f MB). Signals now come only from Kronos.".format(s.sizeMb))
                is KronosModelState.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            val busy = state is KronosModelState.Downloading || state is KronosModelState.Verifying
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GothicButton(
                    text = if (state is KronosModelState.Installed) "Re-download / Update" else "Download",
                    role = GothicRole.PURPLE, enabled = !busy,
                    onClick = { KronosPrefs.setModelUrl(context, url); scope.launch { KronosModelManager.download() } }
                )
                if (state is KronosModelState.Installed) {
                    GothicButton(text = "Delete", role = GothicRole.PURPLE, filled = false, compact = true, enabled = !busy,
                        onClick = { KronosModelManager.delete() })
                }
            }
        }

        SectionCard("Signal threshold (Live + Backtest)") {
            Text("A signal is issued only if at least this share of Kronos' sampled futures agree on the colour of the 5-minute candle's close. Applies to Live and Backtest.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { t ->
                    thresholdText = t
                    t.replace(',', '.').toFloatOrNull()?.let { pct ->
                        if (pct in 50f..100f) { threshold = pct / 100f; KronosPrefs.setThreshold(context, threshold) }
                    }
                },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Threshold % (50 - 100)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
            )
            Slider(value = threshold.coerceIn(0.5f, 1f), onValueChange = {
                threshold = it; thresholdText = "%.1f".format(it * 100f).removeSuffix(".0")
            }, valueRange = 0.50f..1.0f, onValueChangeFinished = { KronosPrefs.setThreshold(context, threshold) })
            Text("Now: ${"%.1f".format(threshold * 100f)}%. 50% = signal on every candle (majority vote). Higher = fewer signals.", style = MaterialTheme.typography.bodySmall)
            Text("Tip: run Backtest once; the model's votes are cached, so changing the threshold and re-running Backtest is fast.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            GothicButton(text = "Clear vote cache", role = GothicRole.PURPLE, filled = false, compact = true,
                onClick = { com.btcsignal.app.kronos.KronosEngine.clearCache(context) })
        }

        SectionCard("Model URL (advanced)") {
            OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("kronos-onnx.zip download link") })
            Text("Filled in automatically when the APK is built by the GitHub workflow.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
