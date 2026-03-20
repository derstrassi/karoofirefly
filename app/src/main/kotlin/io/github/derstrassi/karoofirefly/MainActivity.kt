package io.github.derstrassi.karoofirefly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import io.github.derstrassi.karoofirefly.data.PreferencesRepository
import io.github.derstrassi.karoofirefly.engine.AmbientLightSensor
import io.github.derstrassi.karoofirefly.ui.screens.LightProfileScreen
import io.github.derstrassi.karoofirefly.ui.screens.SettingsScreen
import io.github.derstrassi.karoofirefly.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PreferencesRepository
    private lateinit var luxSensor: AmbientLightSensor
    private var ownsLuxSensor = false

    private enum class Screen { SETTINGS, PROFILES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = PreferencesRepository(applicationContext)
        val extSensor = KarooLightControllerExtension.getInstance()?.ambientLightSensor
        if (extSensor != null) {
            luxSensor = extSensor
        } else {
            luxSensor = AmbientLightSensor(applicationContext)
            ownsLuxSensor = true
        }
        if (ownsLuxSensor) {
            luxSensor.start()
        }

        setContent {
            AppTheme {
                val settings by repository.settingsFlow.collectAsState(initial = LightControllerSettings())
                var currentScreen by remember { mutableStateOf(Screen.SETTINGS) }

                val luxValue by luxSensor.currentLux.collectAsState()

                when (currentScreen) {
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        currentLux = luxValue,
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
                        onSetMode = { mode ->
                            KarooLightControllerExtension.getInstance()?.engine?.setDebugLightMode(mode)
                        },
                    )
                    Screen.PROFILES -> LightProfileScreen(
                        profile = settings.profile,
                        onSave = { newProfile ->
                            lifecycleScope.launch {
                                repository.updateProfile(newProfile)
                                KarooLightControllerExtension.getInstance()?.let { ext ->
                                    ext.engine.settings = ext.engine.settings.copy(profile = newProfile)
                                }
                            }
                            currentScreen = Screen.SETTINGS
                        },
                        onBack = { currentScreen = Screen.SETTINGS },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (ownsLuxSensor) {
            luxSensor.stop()
        }
        super.onDestroy()
    }
}
