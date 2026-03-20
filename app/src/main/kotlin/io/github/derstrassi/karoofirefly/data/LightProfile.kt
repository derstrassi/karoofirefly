package io.github.derstrassi.karoofirefly.data

import io.github.derstrassi.karoofirefly.ant.LightMode
import kotlinx.serialization.Serializable

/**
 * Defines which light mode to use for each time-of-day zone.
 */
@Serializable
data class LightProfile(
    val dayModeFront: Int = LightMode.SLOW_FLASH.modeNumber,
    val dayModeRear: Int = LightMode.SLOW_FLASH.modeNumber,
    val duskModeFront: Int = LightMode.STEADY_LOW.modeNumber,
    val duskModeRear: Int = LightMode.STEADY_HIGH.modeNumber,
    val nightModeFront: Int = LightMode.STEADY_HIGH.modeNumber,
    val nightModeRear: Int = LightMode.STEADY_HIGH.modeNumber,
)

enum class DayTimeZone {
    DAY,
    DUSK,  // also used for dawn
    NIGHT,
}

enum class LightControlMode {
    MANUAL_ONLY,
    TIME_BASED,
    AMBIENT_LIGHT,
    COMBINED,
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
    val ambientDarkThreshold: Int = 50,
    val ambientDimThreshold: Int = 200,
) {
    val controlMode: LightControlMode
        get() = try {
            LightControlMode.valueOf(lightControlMode)
        } catch (_: IllegalArgumentException) {
            LightControlMode.TIME_BASED
        }
}
