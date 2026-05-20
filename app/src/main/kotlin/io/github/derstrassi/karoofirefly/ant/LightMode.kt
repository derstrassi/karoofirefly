package io.github.derstrassi.karoofirefly.ant

enum class LightMode(val modeNumber: Int, val displayName: String, val karooName: String) {
    OFF(0, "Off", "OFF"),
    STEADY_1(1, "Steady 1", "STEADY1"),
    STEADY_2(2, "Steady 2", "STEADY2"),
    STEADY_3(3, "Steady 3", "STEADY3"),
    STEADY_HIGH(4, "Steady High", "STEADY4"),
    STEADY_LOW(5, "Steady Low", "STEADY5"),
    SLOW_FLASH(6, "Slow Flash", "SLOW_FLASH"),
    FAST_FLASH(7, "Fast Flash", "FAST_FLASH"),
    RANDOM_FLASH(8, "Random Flash", "RANDOM_FLASH"),
    AUTO(9, "Auto", "AUTO"),
    SIGNAL_LEFT_AUTO(10, "Signal Left Auto", "SIGNAL_LEFT_AUTO"),
    SIGNAL_LEFT(11, "Signal Left", "SIGNAL_LEFT"),
    SIGNAL_RIGHT_AUTO(12, "Signal Right Auto", "SIGNAL_RIGHT_AUTO"),
    SIGNAL_RIGHT(13, "Signal Right", "SIGNAL_RIGHT"),
    HAZARD(14, "Hazard", "HAZARD"),
    CUSTOM_1(15, "Custom 1", "CUSTOM_MODE_1"),
    CUSTOM_2(16, "Custom 2", "CUSTOM_MODE_2"),
    CUSTOM_3(17, "Custom 3", "CUSTOM_MODE_3"),
    CUSTOM_4(18, "Custom 4", "CUSTOM_MODE_4"),
    CUSTOM_5(19, "Custom 5", "CUSTOM_MODE_5"),
    CUSTOM_6(20, "Custom 6", "CUSTOM_MODE_6"),
    CUSTOM_7(21, "Custom 7", "CUSTOM_MODE_7"),
    CUSTOM_8(22, "Custom 8", "CUSTOM_MODE_8"),
    ;

    companion object {
        fun fromModeNumber(number: Int): LightMode? {
            return entries.find { it.modeNumber == number }
        }

        fun fromKarooName(name: String): LightMode? {
            return entries.find { it.karooName == name }
        }

        val FALLBACK_MODES = listOf(OFF, STEADY_HIGH, STEADY_LOW, SLOW_FLASH, FAST_FLASH)
    }
}
