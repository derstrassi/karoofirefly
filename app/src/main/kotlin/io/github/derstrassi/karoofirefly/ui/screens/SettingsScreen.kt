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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import io.github.derstrassi.karoofirefly.R
import io.github.derstrassi.karoofirefly.ant.LightMode
import io.github.derstrassi.karoofirefly.data.LightControlMode
import io.github.derstrassi.karoofirefly.data.LightControllerSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: LightControllerSettings,
    currentLux: Float = 0f,
    onSave: (LightControllerSettings) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onDebugToggle: (Boolean) -> Unit = {},
    onSetMode: (LightMode) -> Unit = {},
) {
    var dawnOffset by remember(settings) { mutableFloatStateOf(settings.dawnOffsetMinutes.toFloat()) }
    var duskOffset by remember(settings) { mutableFloatStateOf(settings.duskOffsetMinutes.toFloat()) }
    var autoOn by remember(settings) { mutableStateOf(settings.autoOnWithRide) }
    var autoOff by remember(settings) { mutableStateOf(settings.autoOffWithRide) }
    var controlMode by remember(settings) { mutableStateOf(settings.controlMode) }
    var darkThreshold by remember(settings) { mutableIntStateOf(settings.ambientDarkThreshold) }
    var dimThreshold by remember(settings) { mutableIntStateOf(settings.ambientDimThreshold) }

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

        Spacer(modifier = Modifier.height(24.dp))

        // Light Control Mode dropdown
        Text("Light Control Mode", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))

        val modeLabels = mapOf(
            LightControlMode.MANUAL_ONLY to "Off (BonusButton only)",
            LightControlMode.TIME_BASED to "Time-based (sunrise/sunset)",
            LightControlMode.AMBIENT_LIGHT to "Ambient Light Sensor",
            LightControlMode.COMBINED to "Combined (time + sensor)",
        )
        var modeDropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = modeDropdownExpanded,
            onExpandedChange = { modeDropdownExpanded = it },
        ) {
            TextField(
                value = modeLabels[controlMode] ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = modeDropdownExpanded,
                onDismissRequest = { modeDropdownExpanded = false },
            ) {
                LightControlMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(modeLabels[mode] ?: mode.name) },
                        onClick = {
                            controlMode = mode
                            modeDropdownExpanded = false
                        },
                    )
                }
            }
        }

        // Ambient light threshold sliders (visible for sensor modes)
        if (controlMode == LightControlMode.AMBIENT_LIGHT || controlMode == LightControlMode.COMBINED) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Current: %.1f Lux".format(currentLux),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Sensor updates on light change or movement",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text("Night below: $darkThreshold Lux")
            Slider(
                value = darkThreshold.toFloat(),
                onValueChange = { darkThreshold = it.toInt() },
                valueRange = 10f..200f,
                steps = 18,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Day above: $dimThreshold Lux")
            Slider(
                value = dimThreshold.toFloat(),
                onValueChange = { dimThreshold = it.toInt() },
                valueRange = 50f..500f,
                steps = 44,
            )
        }

        if (controlMode != LightControlMode.MANUAL_ONLY) {
            // Dawn/Dusk offsets (only for time-based modes)
            if (controlMode == LightControlMode.TIME_BASED || controlMode == LightControlMode.COMBINED) {
                Spacer(modifier = Modifier.height(16.dp))

                Text("Dawn Offset: ${dawnOffset.toInt()} min")
                Slider(
                    value = dawnOffset,
                    onValueChange = { dawnOffset = it },
                    valueRange = 0f..60f,
                    steps = 11,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Dusk Offset: ${duskOffset.toInt()} min")
                Slider(
                    value = duskOffset,
                    onValueChange = { duskOffset = it },
                    valueRange = 0f..60f,
                    steps = 11,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto on/off toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-on with ride")
                Switch(checked = autoOn, onCheckedChange = { autoOn = it })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-off with ride")
                Switch(checked = autoOff, onCheckedChange = { autoOff = it })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
            var debugModeExpanded by remember { mutableStateOf(false) }
            var selectedMode by remember { mutableStateOf(LightMode.OFF) }
            ExposedDropdownMenuBox(
                expanded = debugModeExpanded,
                onExpandedChange = { debugModeExpanded = it },
            ) {
                TextField(
                    value = selectedMode.displayName,
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
                                selectedMode = mode
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

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                onSave(
                    settings.copy(
                        dawnOffsetMinutes = dawnOffset.toInt(),
                        duskOffsetMinutes = duskOffset.toInt(),
                        autoOnWithRide = autoOn,
                        autoOffWithRide = autoOff,
                        lightControlMode = controlMode.name,
                        ambientDarkThreshold = darkThreshold,
                        ambientDimThreshold = dimThreshold,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}
