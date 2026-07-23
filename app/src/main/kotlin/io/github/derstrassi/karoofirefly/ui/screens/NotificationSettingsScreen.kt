package io.github.derstrassi.karoofirefly.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.data.LightControllerSettings

@Composable
fun NotificationSettingsScreen(
    settings: LightControllerSettings,
    onSave: (LightControllerSettings) -> Unit,
    onBack: () -> Unit,
) {
    var popupDuration by remember(settings) { mutableIntStateOf(settings.popupDurationSeconds) }
    var zoneSound by remember(settings) { mutableStateOf(settings.zoneNotifySound) }
    var zonePopup by remember(settings) { mutableStateOf(settings.zoneNotifyPopup) }
    var batterySound by remember(settings) { mutableStateOf(settings.batteryNotifySound) }
    var batteryPopup by remember(settings) { mutableStateOf(settings.batteryNotifyPopup) }
    var batteryThreshold by remember(settings) { mutableIntStateOf(settings.batteryAlertThreshold) }
    var connectionSound by remember(settings) { mutableStateOf(settings.connectionNotifySound) }
    var connectionPopup by remember(settings) { mutableStateOf(settings.connectionNotifyPopup) }

    fun save() {
        onSave(
            settings.copy(
                popupDurationSeconds = popupDuration,
                zoneNotifySound = zoneSound,
                zoneNotifyPopup = zonePopup,
                batteryNotifySound = batterySound,
                batteryNotifyPopup = batteryPopup,
                batteryAlertThreshold = batteryThreshold,
                connectionNotifySound = connectionSound,
                connectionNotifyPopup = connectionPopup,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 12.dp),
            )
            Text("Notifications", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Popup duration: $popupDuration s")
        Slider(
            value = popupDuration.toFloat(),
            onValueChange = { popupDuration = it.toInt() },
            onValueChangeFinished = { save() },
            valueRange = 3f..60f,
            steps = 56,
        )

        NotificationGroup("Zone change") {
            SwitchRow("Sound", zoneSound) { zoneSound = it; save() }
            SwitchRow("Popup", zonePopup) { zonePopup = it; save() }
        }

        NotificationGroup("Battery low") {
            SwitchRow("Sound", batterySound) { batterySound = it; save() }
            SwitchRow("Popup", batteryPopup) { batteryPopup = it; save() }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Battery below: $batteryThreshold %")
            Slider(
                value = batteryThreshold.toFloat(),
                onValueChange = { batteryThreshold = it.toInt() },
                onValueChangeFinished = { save() },
                valueRange = 5f..50f,
                steps = 44,
            )
        }

        NotificationGroup("Connection lost") {
            SwitchRow("Sound", connectionSound) { connectionSound = it; save() }
            SwitchRow("Popup", connectionPopup) { connectionPopup = it; save() }
        }

        if (settings.lightAssignments.isNotEmpty()) {
            NotificationGroup("Lights") {
                settings.lightAssignments.forEach { assignment ->
                    SwitchRow(assignment.deviceName, assignment.notificationsActive) { enabled ->
                        onSave(
                            settings.copy(
                                lightAssignments = settings.lightAssignments.map {
                                    if (it.deviceId == assignment.deviceId) {
                                        it.copy(notificationsEnabled = enabled)
                                    } else {
                                        it
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationGroup(title: String, content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    content()
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
