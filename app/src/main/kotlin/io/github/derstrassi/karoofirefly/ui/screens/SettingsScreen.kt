package io.github.derstrassi.karoofirefly.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.data.LightControllerSettings

@Composable
fun SettingsScreen(
    settings: LightControllerSettings,
    onSave: (LightControllerSettings) -> Unit,
    onNavigateToProfiles: () -> Unit,
) {
    var dawnOffset by remember(settings) { mutableFloatStateOf(settings.dawnOffsetMinutes.toFloat()) }
    var duskOffset by remember(settings) { mutableFloatStateOf(settings.duskOffsetMinutes.toFloat()) }
    var autoOn by remember(settings) { mutableStateOf(settings.autoOnWithRide) }
    var autoOff by remember(settings) { mutableStateOf(settings.autoOffWithRide) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("KarooFireFly Settings", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(24.dp))

        // Dawn offset
        Text("Dawn Offset: ${dawnOffset.toInt()} min")
        Slider(
            value = dawnOffset,
            onValueChange = { dawnOffset = it },
            valueRange = 0f..60f,
            steps = 11,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Dusk offset
        Text("Dusk Offset: ${duskOffset.toInt()} min")
        Slider(
            value = duskOffset,
            onValueChange = { duskOffset = it },
            valueRange = 0f..60f,
            steps = 11,
        )

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
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}
