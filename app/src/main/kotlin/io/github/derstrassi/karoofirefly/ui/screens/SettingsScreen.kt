package io.github.derstrassi.karoofirefly.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import io.github.derstrassi.karoofirefly.BuildConfig
import io.github.derstrassi.karoofirefly.DiscoveredLight
import io.github.derstrassi.karoofirefly.R
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import io.github.derstrassi.karoofirefly.data.LightAssignment
import io.github.derstrassi.karoofirefly.data.LightControlMode
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import io.github.derstrassi.karoofirefly.data.LightProtocol
import io.github.derstrassi.karoofirefly.data.LightRole
import io.github.derstrassi.karoofirefly.data.modeProviderFor
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: LightControllerSettings,
    discoveredLights: List<DiscoveredLight> = emptyList(),
    currentLux: Float = 0f,
    sunriseTime: Calendar? = null,
    sunsetTime: Calendar? = null,
    onSave: (LightControllerSettings) -> Unit,
    onUpdateAssignment: (String, LightAssignment?) -> Unit = { _, _ -> },
    onDeleteLight: (DiscoveredLight) -> Unit = {},
    onTestMode: (String, String) -> Unit = { _, _ -> },
) {
    var dawnOffset by remember(settings) { mutableFloatStateOf(settings.dawnOffsetMinutes.toFloat()) }
    var duskOffset by remember(settings) { mutableFloatStateOf(settings.duskOffsetMinutes.toFloat()) }
    var autoOn by remember(settings) { mutableStateOf(settings.autoOnWithRide) }
    var autoOff by remember(settings) { mutableStateOf(settings.autoOffWithRide) }
    var autoOffPause by remember(settings) { mutableStateOf(settings.autoOffOnPause) }
    var keepOnAtNight by remember(settings) { mutableStateOf(settings.keepLightsOnAtNightWhilePaused) }
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
                autoOffOnPause = autoOffPause,
                keepLightsOnAtNightWhilePaused = keepOnAtNight,
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
            "Automatic ANT+ and Bluetooth light control based on time of day and ambient light.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connected Lights
        Text("Connected Lights", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        var selectedLight by remember { mutableStateOf<DiscoveredLight?>(null) }

        val discoveredIds = discoveredLights.map { it.id }.toSet()
        val savedButNotFound = settings.lightAssignments.filter { it.deviceId !in discoveredIds }

        val allLights = (discoveredLights + savedButNotFound.map {
            DiscoveredLight(it.deviceId, it.deviceName, null, it.protocol, connected = false)
        }).sortedWith(compareBy<DiscoveredLight> {
            val isConfigured = settings.lightAssignments.any { a -> a.deviceId == it.id }
            when {
                !isConfigured -> 0
                it.connected -> 1
                else -> 2
            }
        })

        if (allLights.isEmpty()) {
            Text(
                "No lights found. Pair lights in Karoo's sensor settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            allLights.forEachIndexed { index, light ->
                val assignment = settings.lightAssignments.find { it.deviceId == light.id }
                LightRow(
                    light = light,
                    role = assignment?.role,
                    onClick = { selectedLight = light },
                )
                if (index < allLights.lastIndex) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        selectedLight?.let { light ->
            val assignment = settings.lightAssignments.find { it.deviceId == light.id }
            LightDetailDialog(
                light = light,
                assignment = assignment,
                onUpdateAssignment = { updated ->
                    onUpdateAssignment(light.id, updated)
                },
                onTestMode = { deviceId, modeName -> onTestMode(deviceId, modeName) },
                onDelete = { onDeleteLight(light) },
                onDismiss = { selectedLight = null },
            )
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

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

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
                Text("Auto-off on pause", modifier = Modifier.weight(1f))
                Switch(checked = autoOffPause, onCheckedChange = { autoOffPause = it; saveSettings() })
            }

            if (autoOffPause) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep on at night", modifier = Modifier.weight(1f))
                    Switch(checked = keepOnAtNight, onCheckedChange = { keepOnAtNight = it; saveSettings() })
                }
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

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Text("Supported Lights", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "ANT+: Garmin Varia, Bontrager Ion/Flare, Magene L508, and other ANT+ smart bike lights paired through Karoo.\n\nBLE: Magicshine Hori 1300, EVO 1300, EVO 1700, and other M1/M2/M3 series lights.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ProtocolBadge(protocol: LightProtocol) {
    val badgeShape = RoundedCornerShape(4.dp)
    when (protocol) {
        LightProtocol.BLE -> {
            Row(
                modifier = Modifier
                    .background(Color(0xFF1565C0), badgeShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = "BLE",
                    modifier = Modifier.size(10.dp),
                    tint = Color.White,
                )
                Text(
                    "BLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 9.sp,
                )
            }
        }
        LightProtocol.ANT_PLUS -> {
            Text(
                "ANT+",
                modifier = Modifier
                    .border(1.dp, Color(0xFF616161), badgeShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF616161),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun LightRow(
    light: DiscoveredLight,
    role: LightRole?,
    onClick: () -> Unit,
) {
    val isConfigured = role != null
    val alpha = if (light.connected || !isConfigured) 1f else 0.4f
    val roleLabel = when (role) {
        LightRole.FRONT -> "Front"
        LightRole.REAR -> "Rear"
        null -> "Tap to configure"
    }
    val statusColor = when {
        !isConfigured -> MaterialTheme.colorScheme.tertiary
        !light.connected -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val backgroundColor = when {
        !isConfigured -> Color(0xFFFFE082)
        !light.connected -> Color(0xFFE0E0E0)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape = MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(alpha)) {
            Text(light.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProtocolBadge(light.protocol)
                val infoText = listOfNotNull(
                    light.manufacturer,
                    if (!light.connected) "Disconnected" else null,
                ).joinToString(" · ")
                if (infoText.isNotEmpty()) {
                    Text(
                        infoText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            roleLabel,
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
        )
    }
}
