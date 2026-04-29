package io.github.derstrassi.karoofirefly.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.ant.LightMode
import io.github.derstrassi.karoofirefly.data.LightProfile

@Composable
fun LightProfileScreen(
    profile: LightProfile,
    onSave: (LightProfile) -> Unit,
    onBack: () -> Unit,
) {
    var dayFront by remember(profile) { mutableIntStateOf(profile.dayModeFront) }
    var dayRear by remember(profile) { mutableIntStateOf(profile.dayModeRear) }
    var duskFront by remember(profile) { mutableIntStateOf(profile.duskModeFront) }
    var duskRear by remember(profile) { mutableIntStateOf(profile.duskModeRear) }
    var nightFront by remember(profile) { mutableIntStateOf(profile.nightModeFront) }
    var nightRear by remember(profile) { mutableIntStateOf(profile.nightModeRear) }

    fun saveProfile() {
        onSave(
            LightProfile(
                dayModeFront = dayFront,
                dayModeRear = dayRear,
                duskModeFront = duskFront,
                duskModeRear = duskRear,
                nightModeFront = nightFront,
                nightModeRear = nightRear,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Light Profiles", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Configure which light mode to use for each time of day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Day
        Text("Day", style = MaterialTheme.typography.titleMedium)
        ModeSelector("Front", dayFront) { dayFront = it; saveProfile() }
        ModeSelector("Rear", dayRear) { dayRear = it; saveProfile() }

        Spacer(modifier = Modifier.height(16.dp))

        // Dusk/Dawn
        Text("Dusk / Dawn", style = MaterialTheme.typography.titleMedium)
        ModeSelector("Front", duskFront) { duskFront = it; saveProfile() }
        ModeSelector("Rear", duskRear) { duskRear = it; saveProfile() }

        Spacer(modifier = Modifier.height(16.dp))

        // Night
        Text("Night", style = MaterialTheme.typography.titleMedium)
        ModeSelector("Front", nightFront) { nightFront = it; saveProfile() }
        ModeSelector("Rear", nightRear) { nightRear = it; saveProfile() }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    label: String,
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = LightMode.CYCLING_MODES
    val selectedLabel = LightMode.fromModeNumber(selectedMode)?.displayName ?: "Mode $selectedMode"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        TextField(
            value = "$label: $selectedLabel",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
                        onModeSelected(mode.modeNumber)
                        expanded = false
                    },
                )
            }
        }
    }
}
