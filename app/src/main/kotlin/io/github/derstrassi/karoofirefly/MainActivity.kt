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
import io.github.derstrassi.karoofirefly.ui.screens.LightProfileScreen
import io.github.derstrassi.karoofirefly.ui.screens.SettingsScreen
import io.github.derstrassi.karoofirefly.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PreferencesRepository

    private enum class Screen { SETTINGS, PROFILES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = PreferencesRepository(applicationContext)

        setContent {
            AppTheme {
                val settings by repository.settingsFlow.collectAsState(initial = LightControllerSettings())
                var currentScreen by remember { mutableStateOf(Screen.SETTINGS) }

                when (currentScreen) {
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSave = { newSettings ->
                            lifecycleScope.launch {
                                repository.updateSettings(newSettings)
                                KarooLightControllerExtension.getInstance()?.let { ext ->
                                    ext.engine.settings = newSettings
                                    ext.timeController.dawnOffsetMinutes = newSettings.dawnOffsetMinutes
                                    ext.timeController.duskOffsetMinutes = newSettings.duskOffsetMinutes
                                }
                            }
                        },
                        onNavigateToProfiles = { currentScreen = Screen.PROFILES },
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
}
