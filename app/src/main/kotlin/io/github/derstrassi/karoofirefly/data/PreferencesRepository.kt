package io.github.derstrassi.karoofirefly.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "light_controller_settings")

class PreferencesRepository(private val context: Context) {

    companion object {
        private val SETTINGS_KEY = stringPreferencesKey("settings")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val settingsFlow: Flow<LightControllerSettings> = context.dataStore.data.map { preferences ->
        getCurrentSettings(preferences)
    }

    suspend fun updateSettings(settings: LightControllerSettings) {
        context.dataStore.edit { preferences ->
            preferences[SETTINGS_KEY] = json.encodeToString(settings)
        }
    }

    suspend fun updateProfile(profile: LightProfile) {
        context.dataStore.edit { preferences ->
            val current = getCurrentSettings(preferences)
            preferences[SETTINGS_KEY] = json.encodeToString(current.copy(profile = profile))
        }
    }

    private fun getCurrentSettings(preferences: Preferences): LightControllerSettings {
        val settingsJson = preferences[SETTINGS_KEY]
        return if (settingsJson != null) {
            try {
                json.decodeFromString<LightControllerSettings>(settingsJson)
            } catch (_: Exception) {
                LightControllerSettings()
            }
        } else {
            LightControllerSettings()
        }
    }
}
