package io.github.derstrassi.karoofirefly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import io.github.derstrassi.karoofirefly.data.PreferencesRepository
import io.github.derstrassi.karoofirefly.engine.AmbientLightSensor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import io.github.derstrassi.karoofirefly.ui.screens.SettingsScreen
import io.github.derstrassi.karoofirefly.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PreferencesRepository
    private lateinit var luxSensor: AmbientLightSensor
    private var ownsLuxSensor = false

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

                val luxValue by luxFlow.collectAsState(initial = luxSensor.currentLux.value)
                val lights = KarooLightControllerExtension.getInstance()
                    ?.discoveredLights?.collectAsState(initial = emptyList())?.value ?: emptyList()

                SettingsScreen(
                    settings = settings,
                    discoveredLights = lights,
                    currentLux = luxValue,
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
                    onUpdateAssignment = { deviceId, updated ->
                        lifecycleScope.launch {
                            val newAssignments = settings.lightAssignments
                                .filter { it.deviceId != deviceId }
                                .let { list -> if (updated != null) list + updated else list }
                            val newSettings = settings.copy(lightAssignments = newAssignments)
                            repository.updateSettings(newSettings)
                            KarooLightControllerExtension.getInstance()?.let { ext ->
                                ext.engine.settings = newSettings
                                ext.onAssignmentChanged()
                            }
                        }
                    },
                    onDisconnectBle = { deviceId ->
                        KarooLightControllerExtension.getInstance()?.magicshineController?.disconnect(deviceId)
                        lifecycleScope.launch {
                            val newAssignments = settings.lightAssignments.filter { it.deviceId != deviceId }
                            val newSettings = settings.copy(lightAssignments = newAssignments)
                            repository.updateSettings(newSettings)
                            KarooLightControllerExtension.getInstance()?.let { ext ->
                                ext.engine.settings = newSettings
                            }
                        }
                    },
                    onTestMode = { deviceId, modeName ->
                        KarooLightControllerExtension.getInstance()?.testMode(deviceId, modeName)
                    },
                )
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
