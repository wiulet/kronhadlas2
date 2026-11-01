package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.ui.components.GothicButton
import com.btcsignal.app.ui.components.GothicPageHeader
import com.btcsignal.app.ui.components.GothicRole
import com.btcsignal.app.ui.components.GothicSwitch
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.TabSettingsColor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val notificationHelper = remember { AppContainer.notificationHelper(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        GothicPageHeader(title = "Settings", accent = TabSettingsColor)

        SectionCard("Notifications") {
            SettingRow("Notifications", settings.notificationsEnabled) {
                scope.launch { settingsRepo.setNotificationsEnabled(it) }
            }
            SettingRow("Sound", settings.soundEnabled) {
                scope.launch { settingsRepo.setSoundEnabled(it) }
            }
            SettingRow("Vibration", settings.vibrationEnabled) {
                scope.launch { settingsRepo.setVibrationEnabled(it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GothicButton(
                    text = "Test Notification",
                    role = GothicRole.PURPLE,
                    filled = false,
                    compact = true,
                    onClick = {
                        notificationHelper.sendTestNotification(settings.soundEnabled, settings.vibrationEnabled)
                    }
                )
                GothicButton(
                    text = "Test Sound",
                    role = GothicRole.PURPLE,
                    filled = false,
                    compact = true,
                    onClick = {
                        notificationHelper.sendTestNotification(soundEnabled = true, vibrationEnabled = settings.vibrationEnabled)
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        GothicSwitch(checked = checked, onCheckedChange = onChange)
    }
}
