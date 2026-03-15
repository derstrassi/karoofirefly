package io.github.derstrassi.karoofirefly.ant

/**
 * ANT+ Bike Light standard modes (Table 5-1 of device profile).
 */
enum class LightMode(val modeNumber: Int, val displayName: String) {
    OFF(0, "Off"),
    STEADY_HIGH(1, "High (81-100%)"),
    STEADY_MEDIUM_HIGH(2, "Medium-High (61-80%)"),
    STEADY_MEDIUM(3, "Medium (41-60%)"),
    STEADY_MEDIUM_LOW(4, "Low (21-40%)"),
    STEADY_LOW(5, "Very Low (0-20%)"),
    SLOW_FLASH(6, "Slow Flash"),
    FAST_FLASH(7, "Fast Flash"),
    RANDOM_FLASH(8, "Random Flash"),
    AUTO(9, "Auto"),
    ;

    /** Map to Karoo's internal LightMode enum name for SensorService AIDL. */
    val karooModeName: String get() = when (this) {
        OFF -> "OFF"
        STEADY_HIGH -> "STEADY1"
        STEADY_MEDIUM_HIGH -> "STEADY2"
        STEADY_MEDIUM -> "STEADY3"
        STEADY_MEDIUM_LOW -> "STEADY4"
        STEADY_LOW -> "STEADY5"
        SLOW_FLASH -> "SLOW_FLASH"
        FAST_FLASH -> "FAST_FLASH"
        RANDOM_FLASH -> "RANDOM_FLASH"
        AUTO -> "AUTO"
    }

    companion object {
        fun fromModeNumber(number: Int): LightMode? {
            return entries.find { it.modeNumber == number }
        }

        val CYCLING_MODES = listOf(OFF, STEADY_HIGH, STEADY_MEDIUM, STEADY_LOW, SLOW_FLASH, FAST_FLASH)
    }
}
