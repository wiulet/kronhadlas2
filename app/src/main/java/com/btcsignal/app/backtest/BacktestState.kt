package com.btcsignal.app.backtest

import android.content.Context
import com.btcsignal.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Singleton, UI-observable snapshot of the backtest run -- mirrors
 * live/LiveEngineState.kt's fix for the same class of bug.
 *
 * BacktestScreen used to keep isRunning/progress/result in composable-local
 * `remember` and launch the run on `rememberCoroutineScope()`. Both are torn
 * down whenever the composition is disposed, which happens every time the
 * person switches bottom-nav tabs (Live/Strategies/History/...) -- so leaving
 * the Backtest tab while a run was in flight silently cancelled it. Owning
 * the state and the coroutine here, in a scope tied to the process instead of
 * any one composable, means a run keeps going -- and keeps reporting progress
 * -- no matter how many times the person switches tabs and comes back.
 */
object BacktestState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val selectedPeriod = MutableStateFlow(7)
    val isRunning = MutableStateFlow(false)
    val progress = MutableStateFlow<BacktestProgress?>(null)
    val result = MutableStateFlow<BacktestSummary?>(null)
    val error = MutableStateFlow<String?>(null)

    /** No-op if a run is already in progress, so tapping the button again after
     *  switching tabs and back doesn't start a second overlapping run. */
    fun start(context: Context) {
        if (isRunning.value) return
        val periodDays = selectedPeriod.value
        val appContext = context.applicationContext

        isRunning.value = true
        error.value = null
        result.value = null
        progress.value = BacktestProgress(BacktestPhase.FETCHING, 0, "Starting\u2026")

        job = scope.launch {
            try {
                val db = AppContainer.strategyDatabase(appContext)
                val engine = AppContainer.backtestEngine(appContext)
                // Read the current block list at run-start so a strategy blocked on the
                // Strategies screen is excluded here too, not just on the Live path.
                val blockedStrategyIds = AppContainer.settingsRepository(appContext)
                    .settingsFlow.first().blockedStrategyIds
                val summary = engine.run(
                    db,
                    periodDays,
                    blockedStrategyIds = blockedStrategyIds
                ) { p -> progress.value = p }
                result.value = summary
            } catch (e: Exception) {
                error.value = e.message ?: "Backtest failed"
            } finally {
                isRunning.value = false
            }
        }
    }
}
