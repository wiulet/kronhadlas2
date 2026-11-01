package com.btcsignal.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "btc_signal_settings")

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val blockedStrategyIds: Set<String> = emptySet()
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val BLOCKED_STRATEGY_IDS = stringSetPreferencesKey("blocked_strategy_ids")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            soundEnabled = prefs[Keys.SOUND] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            blockedStrategyIds = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet()
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    /** Toggles a strategy's temporary block state (Strategies screen "Block" button).
     * A blocked strategy is skipped by CoreSignalEngine on the Live path only — it
     * cannot emit new signals until unblocked again. */
    suspend fun setStrategyBlocked(strategyId: String, blocked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet()
            prefs[Keys.BLOCKED_STRATEGY_IDS] = if (blocked) current + strategyId else current - strategyId
        }
    }
}
