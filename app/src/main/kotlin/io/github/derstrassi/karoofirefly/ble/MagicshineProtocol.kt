package io.github.derstrassi.karoofirefly.ble

import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.LightModeProvider

object MagicshineProtocol {

    const val SERVICE_UUID = "0000FFE1-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FFE0-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_NAME_PREFIXES = setOf("M2-B0", "M2-BO", "M1-B0", "M1-BO")

    const val MODE_STEADY = 1
    const val MODE_SLOW_FLASH = 2
    const val MODE_FAST_FLASH = 3
    const val MODE_SOS = 4

    private const val FLAG_SAVE: Byte = 0xBB.toByte()

    fun buildBrightCommand(
        whitch: Int,
        channel: Int,
        model: Int,
        bright: Int,
    ): ByteArray {
        val content = ByteArray(14)
        content[0] = whitch.toByte()
        val offset = channel * 3 + 1
        content[offset] = 0x01
        content[offset + 1] = model.toByte()
        content[offset + 2] = bright.toByte()
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    fun buildOffCommand(channel: Int = 0): ByteArray {
        val content = ByteArray(14)
        content[0] = 0x01
        val offset = channel * 3 + 1
        content[offset] = 0x01
        content[offset + 1] = 0x01
        content[offset + 2] = 0x00
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    private fun buildFrame(type: Byte, status: Byte, content: ByteArray): ByteArray {
        val totalLen = 6 + content.size
        val frame = ByteArray(totalLen)
        frame[0] = 0xDE.toByte()
        frame[1] = totalLen.toByte()
        frame[2] = type
        frame[3] = status
        System.arraycopy(content, 0, frame, 4, content.size)

        var cs: Byte = frame[1]
        for (i in 2 until totalLen - 2) {
            cs = (cs.toInt() xor frame[i].toInt()).toByte()
        }
        frame[totalLen - 2] = cs
        frame[totalLen - 1] = 0xED.toByte()
        return frame
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

data class MagicshineDeviceConfig(
    val channel: Int,
    val modes: List<LightModeOption>,
) {
    fun buildCommand(modeId: String): ByteArray? {
        if (modeId == "OFF") return MagicshineProtocol.buildOffCommand(channel)
        val parts = modeId.split("_", limit = 2)
        if (parts.size != 2) return null
        val model = when (parts[0]) {
            "STEADY" -> MagicshineProtocol.MODE_STEADY
            "FLASH" -> MagicshineProtocol.MODE_FAST_FLASH
            "SOS" -> MagicshineProtocol.MODE_SOS
            else -> return null
        }
        val bright = parts[1].toIntOrNull() ?: return null
        return MagicshineProtocol.buildBrightCommand(1, channel, model, bright)
    }

    companion object {
        private val BRIGHTNESS_STEPS = listOf(10, 25, 50, 100)

        fun forDevice(bleName: String): MagicshineDeviceConfig {
            val isM2 = bleName.startsWith("M2", ignoreCase = true)
            val channel = if (isM2) 0 else 1

            val modes = mutableListOf(LightModeOption("OFF", "Off"))
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("STEADY_$b", "Steady $b%"))
            }
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("FLASH_$b", "Flash $b%"))
            }
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("SOS_$b", "SOS $b%"))
            }

            return MagicshineDeviceConfig(channel = channel, modes = modes)
        }
    }
}

class MagicshineDeviceModeProvider(private val config: MagicshineDeviceConfig) : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = config.modes
}
