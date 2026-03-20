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
import timber.log.Timber

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
        val settings = if (settingsJson != null) {
            try {
                json.decodeFromString<LightControllerSettings>(settingsJson)
            } catch (_: Exception) {
                LightControllerSettings()
            }
        } else {
            LightControllerSettings()
        }
        return if (settings.profileVersion < 1) migrateToV1(settings) else settings
    }

    private fun migrateToV1(settings: LightControllerSettings): LightControllerSettings {
        Timber.i("PreferencesRepository: Migrating settings from v${settings.profileVersion} to v1")
        val profile = settings.profile
        val migrated = settings.copy(
            profileVersion = 1,
            profile = LightProfile(
                dayModeFront = migrateMode(profile.dayModeFront),
                dayModeRear = migrateMode(profile.dayModeRear),
                duskModeFront = migrateMode(profile.duskModeFront),
                duskModeRear = migrateMode(profile.duskModeRear),
                nightModeFront = migrateMode(profile.nightModeFront),
                nightModeRear = migrateMode(profile.nightModeRear),
            ),
        )
        Timber.i("PreferencesRepository: Migration complete: $migrated")
        return migrated
    }

    /** Maps old ANT+ spec mode numbers to new persistence IDs. */
    private fun migrateMode(oldModeNumber: Int): Int = when (oldModeNumber) {
        0 -> 0          // OFF → OFF
        1, 2, 3 -> 1    // STEADY_HIGH/MED_HIGH/MED → STEADY_HIGH
        4, 5 -> 2       // MED_LOW/LOW → STEADY_LOW
        6 -> 3          // SLOW_FLASH → SLOW_FLASH
        7 -> 4          // FAST_FLASH → FAST_FLASH
        8 -> 3          // RANDOM_FLASH → SLOW_FLASH (fallback)
        9 -> 0          // AUTO → OFF (fallback)
        else -> 0
    }
}
