package io.github.derstrassi.karoofirefly.data

import io.github.derstrassi.karoofirefly.ant.LightMode
import kotlinx.serialization.Serializable

@Serializable
data class LightProfile(
    val dayModeFront: Int = LightMode.SLOW_FLASH.modeNumber,
    val dayModeRear: Int = LightMode.SLOW_FLASH.modeNumber,
    val nightModeFront: Int = LightMode.STEADY_HIGH.modeNumber,
    val nightModeRear: Int = LightMode.STEADY_HIGH.modeNumber,
)

enum class DayTimeZone {
    DAY,
    NIGHT,
}

enum class LightControlMode {
    MANUAL_ONLY,
    TIME_BASED,
    AMBIENT_LIGHT,
    COMBINED;

    companion object {
        fun fromFlags(timeBased: Boolean, ambientLight: Boolean): LightControlMode = when {
            timeBased && ambientLight -> COMBINED
            timeBased -> TIME_BASED
            ambientLight -> AMBIENT_LIGHT
            else -> MANUAL_ONLY
        }
    }
}

/**
 * Stored settings for the extension.
 */
@Serializable
data class LightControllerSettings(
    val dawnOffsetMinutes: Int = 0,
    val duskOffsetMinutes: Int = 0,
    val autoOnWithRide: Boolean = true,
    val autoOffWithRide: Boolean = true,
    val profile: LightProfile = LightProfile(),
    val lightControlMode: String = "MANUAL_ONLY",
    val ambientNightThreshold: Int = 50,
    val zoneNotificationsEnabled: Boolean = true,
) {
    val controlMode: LightControlMode
        get() = try {
            LightControlMode.valueOf(lightControlMode)
        } catch (_: IllegalArgumentException) {
            LightControlMode.TIME_BASED
        }

    val useTimeBased: Boolean
        get() = controlMode == LightControlMode.TIME_BASED || controlMode == LightControlMode.COMBINED

    val useAmbientLight: Boolean
        get() = controlMode == LightControlMode.AMBIENT_LIGHT || controlMode == LightControlMode.COMBINED
}
