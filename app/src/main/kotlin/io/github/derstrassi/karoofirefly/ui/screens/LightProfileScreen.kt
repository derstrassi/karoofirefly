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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.data.LightAssignment
import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.modeProviderFor

@Composable
fun LightProfileScreen(
    assignments: List<LightAssignment>,
    onUpdateAssignment: (LightAssignment) -> Unit,
    onBack: () -> Unit,
) {
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

        if (assignments.isEmpty()) {
            Text(
                "No lights assigned. Assign lights as Front or Rear in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            assignments.forEachIndexed { index, assignment ->
                val modes = modeProviderFor(assignment.protocol).availableModes()

                Text(
                    "${assignment.deviceName} — ${assignment.role.name}",
                    style = MaterialTheme.typography.titleMedium,
                )

                ModeSelector("Day", assignment.dayMode, modes) { newMode ->
                    onUpdateAssignment(assignment.copy(dayMode = newMode))
                }
                ModeSelector("Night", assignment.nightMode, modes) { newMode ->
                    onUpdateAssignment(assignment.copy(nightMode = newMode))
                }

                if (index < assignments.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

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
    selectedMode: String,
    modes: List<LightModeOption>,
    onModeSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = modes.find { it.id == selectedMode }?.displayName ?: selectedMode

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
                        onModeSelected(mode.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
