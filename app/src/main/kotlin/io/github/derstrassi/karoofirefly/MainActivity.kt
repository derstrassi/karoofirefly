package io.github.derstrassi.karoofirefly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import io.github.derstrassi.karoofirefly.data.PreferencesRepository
import io.github.derstrassi.karoofirefly.engine.AmbientLightSensor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import io.github.derstrassi.karoofirefly.ui.screens.LightProfileScreen
import io.github.derstrassi.karoofirefly.ui.screens.SettingsScreen
import io.github.derstrassi.karoofirefly.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PreferencesRepository
    private lateinit var luxSensor: AmbientLightSensor
    private var ownsLuxSensor = false

    private enum class Screen { SETTINGS, PROFILES }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            KarooLightControllerExtension.getInstance()?.setSettingsUiActive(true)
        }
    }

    private fun ensureBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBlePermissions()
        KarooLightControllerExtension.getInstance()?.setSettingsUiActive(true)

        repository = PreferencesRepository(applicationContext)
        val ext = KarooLightControllerExtension.getInstance()
        if (ext != null) {
            luxSensor = ext.ambientLightSensor
        } else {
            luxSensor = AmbientLightSensor(applicationContext)
            ownsLuxSensor = true
            luxSensor.start()
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val extensionFlow = flow {
            while (true) {
                val instance = KarooLightControllerExtension.getInstance()
                if (instance != null) {
                    emit(instance)
                    return@flow
                }
                delay(500)
            }
        }

        val activeZoneFlow = extensionFlow.flatMapLatest { it.engine.activeZone }
        val luxFlow = extensionFlow.flatMapLatest {
            if (ownsLuxSensor) {
                luxSensor.stop()
                ownsLuxSensor = false
            }
            it.ambientLightSensor.currentLux
        }

        setContent {
            AppTheme {
                val settings by repository.settingsFlow.collectAsState(initial = LightControllerSettings())
                var currentScreen by remember { mutableStateOf(Screen.SETTINGS) }

                val luxValue by luxFlow.collectAsState(initial = luxSensor.currentLux.value)
                val activeZone by activeZoneFlow.collectAsState(initial = null)
                val lights = KarooLightControllerExtension.getInstance()
                    ?.discoveredLights?.collectAsState(initial = emptyList())?.value ?: emptyList()

                when (currentScreen) {
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        discoveredLights = lights,
                        currentLux = luxValue,
                        activeZone = activeZone,
                        sunriseTime = KarooLightControllerExtension.getInstance()?.timeController?.getSunriseTime(),
                        sunsetTime = KarooLightControllerExtension.getInstance()?.timeController?.getSunsetTime(),
                        onSave = { newSettings ->
                            lifecycleScope.launch {
                                repository.updateSettings(newSettings)
                                KarooLightControllerExtension.getInstance()?.let { ext ->
                                    ext.engine.settings = newSettings
                                    ext.timeController.dawnOffsetMinutes = newSettings.dawnOffsetMinutes
                                    ext.timeController.duskOffsetMinutes = newSettings.duskOffsetMinutes
                                    ext.engine.updateAmbientSensor()
                                }
                            }
                        },
                        onNavigateToProfiles = { currentScreen = Screen.PROFILES },
                        onDebugToggle = { enabled ->
                            KarooLightControllerExtension.getInstance()?.engine?.setDebugMode(enabled)
                        },
                        onSetZone = { zone ->
                            KarooLightControllerExtension.getInstance()?.engine?.setDebugZone(zone)
                        },
                    )
                    Screen.PROFILES -> LightProfileScreen(
                        assignments = settings.lightAssignments,
                        onUpdateAssignment = { updated ->
                            lifecycleScope.launch {
                                val newAssignments = settings.lightAssignments.map {
                                    if (it.deviceId == updated.deviceId) updated else it
                                }
                                val newSettings = settings.copy(lightAssignments = newAssignments)
                                repository.updateSettings(newSettings)
                                KarooLightControllerExtension.getInstance()?.let { ext ->
                                    ext.engine.settings = newSettings
                                }
                            }
                        },
                        onBack = { currentScreen = Screen.SETTINGS },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        KarooLightControllerExtension.getInstance()?.setSettingsUiActive(false)
        if (ownsLuxSensor) {
            luxSensor.stop()
        }
        super.onDestroy()
    }
}
