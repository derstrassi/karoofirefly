package io.github.derstrassi.karoofirefly.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.DiscoveredLight
import io.github.derstrassi.karoofirefly.data.LightAssignment
import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.LightProtocol
import io.github.derstrassi.karoofirefly.data.LightRole
import io.github.derstrassi.karoofirefly.data.modeProviderFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightDetailDialog(
    light: DiscoveredLight,
    assignment: LightAssignment?,
    onUpdateAssignment: (LightAssignment?) -> Unit,
    onTestMode: ((String, String) -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val modes = modeProviderFor(light.protocol, light.id).availableModes()
    var role by remember(assignment) { mutableStateOf(assignment?.role) }
    var dayMode by remember(assignment) { mutableStateOf(assignment?.dayMode ?: "OFF") }
    var nightMode by remember(assignment) { mutableStateOf(assignment?.nightMode ?: "OFF") }

    fun save() {
        if (role != null) {
            onUpdateAssignment(
                LightAssignment(
                    deviceId = light.id,
                    deviceName = light.name,
                    role = role!!,
                    protocol = light.protocol,
                    dayMode = dayMode,
                    nightMode = nightMode,
                ),
            )
        } else {
            onUpdateAssignment(null)
        }
    }

    val protocolLabel = when (light.protocol) {
        LightProtocol.ANT_PLUS -> "ANT+"
        LightProtocol.BLE -> "BLE"
    }
    val connectionLabel = if (light.connected) "Connected" else "Not found"

    var showDisconnectConfirm by remember { mutableStateOf(false) }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect") },
            text = { Text("Disconnect ${light.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    onDisconnect?.invoke()
                    onDismiss()
                }) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (onDisconnect != null && light.connected) {
            {
                TextButton(onClick = { showDisconnectConfirm = true }) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }
        } else null,
        title = { Text(light.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Status info
                val statusParts = listOfNotNull(
                    light.manufacturer,
                    protocolLabel,
                    connectionLabel,
                )
                Text(
                    statusParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val telemetryParts = mutableListOf<String>()
                light.batteryPercent?.let { telemetryParts.add("Battery: $it%") }
                light.temperature?.let { telemetryParts.add("Temp: ${it}°C") }
                if (telemetryParts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        telemetryParts.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Role selector
                OptionDropdown(
                    label = "Role",
                    selectedId = role?.name ?: "None",
                    options = listOf("FRONT" to "Front", "REAR" to "Rear", "None" to "None"),
                    onSelected = { selected ->
                        role = when (selected) {
                            "FRONT" -> LightRole.FRONT
                            "REAR" -> LightRole.REAR
                            else -> null
                        }
                        if (role != null && dayMode == "OFF" && nightMode == "OFF") {
                            val defaultMode = modes.getOrNull(1)?.id ?: "OFF"
                            dayMode = defaultMode
                            nightMode = defaultMode
                        }
                        save()
                    },
                )

                if (role != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LightModeWithTest(
                        label = "Day",
                        selectedMode = dayMode,
                        modes = modes,
                        onSelected = { dayMode = it; save() },
                        onTest = if (onTestMode != null && light.connected) {
                            { onTestMode(light.id, dayMode) }
                        } else null,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LightModeWithTest(
                        label = "Night",
                        selectedMode = nightMode,
                        modes = modes,
                        onSelected = { nightMode = it; save() },
                        onTest = if (onTestMode != null && light.connected) {
                            { onTestMode(light.id, nightMode) }
                        } else null,
                    )
                }
            }
        },
    )
}

@Composable
private fun LightModeWithTest(
    label: String,
    selectedMode: String,
    modes: List<LightModeOption>,
    onSelected: (String) -> Unit,
    onTest: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OptionDropdown(
            label = label,
            selectedId = selectedMode,
            options = modes.map { it.id to it.displayName },
            onSelected = onSelected,
            modifier = Modifier.weight(1f),
        )
        if (onTest != null) {
            IconButton(onClick = onTest, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Test $label",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = options.find { it.first == selectedId }?.second ?: selectedId

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        TextField(
            value = "$label: $displayName",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelected(id); expanded = false },
                )
            }
        }
    }
}
