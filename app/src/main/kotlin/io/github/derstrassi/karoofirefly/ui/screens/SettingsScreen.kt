package io.github.derstrassi.karoofirefly.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.DiscoveredLight
import io.github.derstrassi.karoofirefly.R
import io.github.derstrassi.karoofirefly.ant.LightMode
import io.github.derstrassi.karoofirefly.data.LightAssignment
import io.github.derstrassi.karoofirefly.data.LightControlMode
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import io.github.derstrassi.karoofirefly.data.LightRole
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: LightControllerSettings,
    discoveredLights: List<DiscoveredLight> = emptyList(),
    currentLux: Float = 0f,
    currentLightMode: LightMode = LightMode.OFF,
    sunriseTime: Calendar? = null,
    sunsetTime: Calendar? = null,
    onSave: (LightControllerSettings) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onDebugToggle: (Boolean) -> Unit = {},
    onSetMode: (LightMode) -> Unit = {},
    onTestNotification: () -> Unit = {},
) {
    var dawnOffset by remember(settings) { mutableFloatStateOf(settings.dawnOffsetMinutes.toFloat()) }
    var duskOffset by remember(settings) { mutableFloatStateOf(settings.duskOffsetMinutes.toFloat()) }
    var autoOn by remember(settings) { mutableStateOf(settings.autoOnWithRide) }
    var autoOff by remember(settings) { mutableStateOf(settings.autoOffWithRide) }
    var useTimeBased by remember(settings) { mutableStateOf(settings.useTimeBased) }
    var useAmbientLight by remember(settings) { mutableStateOf(settings.useAmbientLight) }
    var nightThreshold by remember(settings) { mutableIntStateOf(settings.ambientNightThreshold) }
    var zoneNotifications by remember(settings) { mutableStateOf(settings.zoneNotificationsEnabled) }

    fun saveSettings() {
        onSave(
            settings.copy(
                dawnOffsetMinutes = dawnOffset.toInt(),
                duskOffsetMinutes = duskOffset.toInt(),
                autoOnWithRide = autoOn,
                autoOffWithRide = autoOff,
                lightControlMode = LightControlMode.fromFlags(useTimeBased, useAmbientLight).name,
                ambientNightThreshold = nightThreshold,
                zoneNotificationsEnabled = zoneNotifications,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("KarooFireFly", style = MaterialTheme.typography.headlineSmall)
            Image(
                painter = painterResource(R.drawable.ic_firefly),
                contentDescription = "KarooFireFly",
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            "Automatic ANT+ light control based on time of day and ambient light.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connected Lights
        Text("Connected Lights", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        if (discoveredLights.isEmpty()) {
            Text(
                "No lights found. Pair lights in Karoo's sensor settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (light in discoveredLights) {
                val currentAssignment = settings.lightAssignments.find { it.deviceId == light.id }
                LightRoleSelector(
                    light = light,
                    currentRole = currentAssignment?.role,
                    onRoleSelected = { role ->
                        val updatedAssignments = settings.lightAssignments
                            .filter { it.deviceId != light.id }
                            .let { list ->
                                if (role != null) {
                                    list + LightAssignment(light.id, light.name, role)
                                } else {
                                    list
                                }
                            }
                        onSave(settings.copy(lightAssignments = updatedAssignments))
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Light Control Mode switches
        Text("Light Control", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Time-based (sunrise/sunset)", modifier = Modifier.weight(1f))
            Switch(checked = useTimeBased, onCheckedChange = { useTimeBased = it; saveSettings() })
        }

        if (useTimeBased) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Spacer(modifier = Modifier.height(12.dp))

                val dayStartText = sunriseTime?.let { sr ->
                    val start = (sr.clone() as Calendar).apply { add(Calendar.MINUTE, dawnOffset.toInt()) }
                    "%02d:%02d".format(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
                }
                Text("Day starts at: ${dayStartText ?: "—"}")
                Text(
                    "Offset: ${dawnOffset.toInt()} min from sunrise",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = dawnOffset,
                    onValueChange = { dawnOffset = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = -180f..180f,
                    steps = 35,
                )

                Spacer(modifier = Modifier.height(8.dp))

                val nightStartText = sunsetTime?.let { ss ->
                    val start = (ss.clone() as Calendar).apply { add(Calendar.MINUTE, duskOffset.toInt()) }
                    "%02d:%02d".format(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
                }
                Text("Night starts at: ${nightStartText ?: "—"}")
                Text(
                    "Offset: ${duskOffset.toInt()} min from sunset",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = duskOffset,
                    onValueChange = { duskOffset = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = -180f..180f,
                    steps = 35,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ambient Light Sensor", modifier = Modifier.weight(1f))
            Switch(checked = useAmbientLight, onCheckedChange = { useAmbientLight = it; saveSettings() })
        }

        if (useAmbientLight) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Current: %.1f Lux".format(currentLux),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Sensor updates on movement only!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Night below: $nightThreshold Lux")
                Slider(
                    value = nightThreshold.toFloat(),
                    onValueChange = { nightThreshold = it.toInt() },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 10f..500f,
                    steps = 48,
                )
            }
        }

        if (useTimeBased || useAmbientLight) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-on with ride", modifier = Modifier.weight(1f))
                Switch(checked = autoOn, onCheckedChange = { autoOn = it; saveSettings() })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-off with ride", modifier = Modifier.weight(1f))
                Switch(checked = autoOff, onCheckedChange = { autoOff = it; saveSettings() })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Zone change notifications", modifier = Modifier.weight(1f))
                Switch(checked = zoneNotifications, onCheckedChange = { zoneNotifications = it; saveSettings() })
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Debug mode toggle
        var debugEnabled by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Debug mode",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = debugEnabled,
                onCheckedChange = {
                    debugEnabled = it
                    onDebugToggle(it)
                },
            )
        }

        if (debugEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onTestNotification, modifier = Modifier.fillMaxWidth()) {
                Text("Test Zone Notification")
            }
            Spacer(modifier = Modifier.height(8.dp))
            var debugModeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = debugModeExpanded,
                onExpandedChange = { debugModeExpanded = it },
            ) {
                TextField(
                    value = currentLightMode.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Light Mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = debugModeExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = debugModeExpanded,
                    onDismissRequest = { debugModeExpanded = false },
                ) {
                    LightMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            onClick = {
                                debugModeExpanded = false
                                onSetMode(mode)
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onNavigateToProfiles, modifier = Modifier.fillMaxWidth()) {
            Text("Light Profiles")
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LightRoleSelector(
    light: DiscoveredLight,
    currentRole: LightRole?,
    onRoleSelected: (LightRole?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val roleLabel = when (currentRole) {
        LightRole.FRONT -> "Front"
        LightRole.REAR -> "Rear"
        null -> "—"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(light.name)
            if (light.manufacturer != null) {
                Text(
                    light.manufacturer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            TextField(
                value = roleLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).width(110.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Front") },
                    onClick = { onRoleSelected(LightRole.FRONT); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("Rear") },
                    onClick = { onRoleSelected(LightRole.REAR); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = { onRoleSelected(null); expanded = false },
                )
            }
        }
    }
}
