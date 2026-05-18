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

@Serializable
enum class LightRole { FRONT, REAR }

@Serializable
enum class LightProtocol { ANT_PLUS, BLE }

@Serializable
data class LightAssignment(
    val deviceId: String,
    val deviceName: String,
    val role: LightRole,
    val protocol: LightProtocol = LightProtocol.ANT_PLUS,
    val dayMode: String = "OFF",
    val nightMode: String = "OFF",
    val radarWarnFlash: Boolean = false,
) {
    fun modeForZone(zone: DayTimeZone): String = when (zone) {
        DayTimeZone.DAY -> dayMode
        DayTimeZone.NIGHT -> nightMode
    }
}

data class LightModeOption(
    val id: String,
    val displayName: String,
)

interface LightModeProvider {
    fun availableModes(): List<LightModeOption>
}

object AntPlusModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> =
        LightMode.CYCLING_MODES.map { LightModeOption(it.karooName, it.displayName) }
}

fun modeProviderFor(protocol: LightProtocol, deviceId: String? = null): LightModeProvider = when (protocol) {
    LightProtocol.ANT_PLUS -> AntPlusModeProvider
    LightProtocol.BLE -> {
        val config = deviceId?.let {
            io.github.derstrassi.karoofirefly.KarooLightControllerExtension.getInstance()
                ?.magicshineController?.getDeviceConfig(it)
        }
        if (config != null) {
            io.github.derstrassi.karoofirefly.ble.MagicshineDeviceModeProvider(config)
        } else {
            io.github.derstrassi.karoofirefly.ble.MagicshineDeviceModeProvider(
                io.github.derstrassi.karoofirefly.ble.MagicshineDeviceConfig.forDevice("M1"),
            )
        }
    }
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

@Serializable
data class LightControllerSettings(
    val dawnOffsetMinutes: Int = 0,
    val duskOffsetMinutes: Int = 0,
    val autoOnWithRide: Boolean = true,
    val autoOffWithRide: Boolean = true,
    val autoOffOnPause: Boolean = false,
    val profile: LightProfile = LightProfile(),
    val lightControlMode: String = "MANUAL_ONLY",
    val ambientNightThreshold: Int = 50,
    val zoneNotificationsEnabled: Boolean = true,
    val lightAssignments: List<LightAssignment> = emptyList(),
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

    fun migrateProfilesToAssignments(): LightControllerSettings {
        if (lightAssignments.isEmpty()) return this
        val profileMigrated = lightAssignments.any { it.dayMode != "OFF" || it.nightMode != "OFF" }
        if (profileMigrated) return this
        val migrated = lightAssignments.map { assignment ->
            val dayMode = when (assignment.role) {
                LightRole.FRONT -> LightMode.fromModeNumber(profile.dayModeFront)?.karooName ?: "OFF"
                LightRole.REAR -> LightMode.fromModeNumber(profile.dayModeRear)?.karooName ?: "OFF"
            }
            val nightMode = when (assignment.role) {
                LightRole.FRONT -> LightMode.fromModeNumber(profile.nightModeFront)?.karooName ?: "OFF"
                LightRole.REAR -> LightMode.fromModeNumber(profile.nightModeRear)?.karooName ?: "OFF"
            }
            assignment.copy(dayMode = dayMode, nightMode = nightMode)
        }
        return copy(lightAssignments = migrated)
    }
}
