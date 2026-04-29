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
import io.github.derstrassi.karoofirefly.R
import io.github.derstrassi.karoofirefly.ant.LightMode
import io.github.derstrassi.karoofirefly.data.LightControlMode
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: LightControllerSettings,
    currentLux: Float = 0f,
    currentLightMode: LightMode = LightMode.OFF,
    sunriseTime: Calendar? = null,
    sunsetTime: Calendar? = null,
    onSave: (LightControllerSettings) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onDebugToggle: (Boolean) -> Unit = {},
    onSetMode: (LightMode) -> Unit = {},
) {
    var dawnOffset by remember(settings) { mutableFloatStateOf(settings.dawnOffsetMinutes.toFloat()) }
    var duskOffset by remember(settings) { mutableFloatStateOf(settings.duskOffsetMinutes.toFloat()) }
    var autoOn by remember(settings) { mutableStateOf(settings.autoOnWithRide) }
    var autoOff by remember(settings) { mutableStateOf(settings.autoOffWithRide) }
    var useTimeBased by remember(settings) { mutableStateOf(settings.useTimeBased) }
    var useAmbientLight by remember(settings) { mutableStateOf(settings.useAmbientLight) }
    var darkThreshold by remember(settings) { mutableIntStateOf(settings.ambientDarkThreshold) }
    var dimThreshold by remember(settings) { mutableIntStateOf(settings.ambientDimThreshold) }

    fun saveSettings() {
        onSave(
            settings.copy(
                dawnOffsetMinutes = dawnOffset.toInt(),
                duskOffsetMinutes = duskOffset.toInt(),
                autoOnWithRide = autoOn,
                autoOffWithRide = autoOff,
                lightControlMode = LightControlMode.fromFlags(useTimeBased, useAmbientLight).name,
                ambientDarkThreshold = darkThreshold,
                ambientDimThreshold = dimThreshold,
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

                val dawnTimeText = sunriseTime?.let { sr ->
                    val start = (sr.clone() as Calendar).apply { add(Calendar.MINUTE, -dawnOffset.toInt()) }
                    "%02d:%02d".format(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
                }
                Text("Dawn Offset: ${dawnOffset.toInt()} min")
                if (dawnTimeText != null) {
                    Text(
                        "Dusk zone starts at $dawnTimeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = dawnOffset,
                    onValueChange = { dawnOffset = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = -120f..120f,
                    steps = 23,
                )

                Spacer(modifier = Modifier.height(8.dp))

                val duskTimeText = sunsetTime?.let { ss ->
                    val start = (ss.clone() as Calendar).apply { add(Calendar.MINUTE, -duskOffset.toInt()) }
                    "%02d:%02d".format(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
                }
                Text("Dusk Offset: ${duskOffset.toInt()} min")
                if (duskTimeText != null) {
                    Text(
                        "Dusk zone starts at $duskTimeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = duskOffset,
                    onValueChange = { duskOffset = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = -120f..120f,
                    steps = 23,
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
                Text("Night below: $darkThreshold Lux")
                Slider(
                    value = darkThreshold.toFloat(),
                    onValueChange = { darkThreshold = it.toInt() },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 10f..200f,
                    steps = 18,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Day above: $dimThreshold Lux")
                Slider(
                    value = dimThreshold.toFloat(),
                    onValueChange = { dimThreshold = it.toInt() },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 50f..500f,
                    steps = 44,
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
