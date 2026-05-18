package io.github.derstrassi.karoofirefly.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derstrassi.karoofirefly.DiscoveredLight
import io.github.derstrassi.karoofirefly.data.LightAssignment
import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.LightProtocol
import io.github.derstrassi.karoofirefly.data.LightRole
import io.github.derstrassi.karoofirefly.data.modeProviderFor

@Composable
fun LightDetailDialog(
    light: DiscoveredLight,
    assignment: LightAssignment?,
    onUpdateAssignment: (LightAssignment?) -> Unit,
    onTestMode: ((String, String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val modes = modeProviderFor(light.protocol, light.id).availableModes()
    var role by remember(assignment) { mutableStateOf(assignment?.role) }
    var dayMode by remember(assignment) { mutableStateOf(assignment?.dayMode ?: "OFF") }
    var nightMode by remember(assignment) { mutableStateOf(assignment?.nightMode ?: "OFF") }
    var radarWarnFlash by remember(assignment) { mutableStateOf(assignment?.radarWarnFlash ?: false) }

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
                    radarWarnFlash = radarWarnFlash,
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

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Light") },
            text = { Text("Remove ${light.name}? Role and mode settings will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke()
                    onDismiss()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
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
        dismissButton = if (onDelete != null && assignment != null) {
            {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        } else null,
        title = { Text(light.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                    Text(
                        telemetryParts.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                InlineDropdown(
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
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    ModeRow(
                        label = "Day",
                        selectedMode = dayMode,
                        modes = modes,
                        onSelected = { dayMode = it; save() },
                        onTest = if (onTestMode != null && light.connected) {
                            { onTestMode(light.id, dayMode) }
                        } else null,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ModeRow(
                        label = "Night",
                        selectedMode = nightMode,
                        modes = modes,
                        onSelected = { nightMode = it; save() },
                        onTest = if (onTestMode != null && light.connected) {
                            { onTestMode(light.id, nightMode) }
                        } else null,
                    )

                    if (role == LightRole.REAR) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Radar Warn Flash")
                                Text(
                                    "Flash when vehicle detected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = radarWarnFlash,
                                onCheckedChange = { radarWarnFlash = it; save() },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ModeRow(
    label: String,
    selectedMode: String,
    modes: List<LightModeOption>,
    onSelected: (String) -> Unit,
    onTest: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineDropdown(
            label = label,
            selectedId = selectedMode,
            options = modes.map { it.id to it.displayName },
            onSelected = onSelected,
            modifier = Modifier.weight(1f),
        )
        if (onTest != null) {
            IconButton(onClick = onTest, modifier = Modifier.size(32.dp)) {
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

@Composable
private fun InlineDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = options.find { it.first == selectedId }?.second ?: selectedId

    Row(
        modifier = modifier
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        DropdownMenu(
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
