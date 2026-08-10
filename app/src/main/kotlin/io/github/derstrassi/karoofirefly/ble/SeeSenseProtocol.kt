package io.github.derstrassi.karoofirefly.ble

import io.github.derstrassi.karoofirefly.data.LightModeOption
import io.github.derstrassi.karoofirefly.data.LightModeProvider

/**
 * BLE protocol for the See.Sense ICON3 headlight.
 *
 * Reverse-engineered from the official See.Sense Android app (cc.seesense).
 *
 * Commands are sent as single ASCII characters to the Nordic UART TX characteristic
 * (6E400002) with WriteType.WITHOUT_RESPONSE.
 *
 * Status notifications arrive on 6E40A102 (device status characteristic) as a 4-byte
 * binary packet [0x03, 0x08, mode, brightness] — these are read-only state reports.
 *
 * Mode commands (from cc.seesense.device.commands.CommandFactory):
 *   '~' (0x7E) — off
 *   'O' (0x4F) — solid
 *   'I' (0x49) — flash (cycling flash pattern)
 *   '.' (0x2E) — flash normal
 *   ':' (0x3A) — flash burst
 *   ',' (0x2C) — flash twin
 *   ';' (0x3B) — flash pulse
 *   'E' (0x45) — flash eco
 */
object SeeSenseProtocol {

    // Nordic UART service — this is where commands are sent
    const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val TX_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val RX_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

    // Status service — subscribe here to receive state notifications
    const val STATUS_SERVICE_UUID = "6E40A100-B5A3-F393-E0A9-E50E24DCCA9E"
    const val STATUS_CHARACTERISTIC_UUID = "6E40A102-B5A3-F393-E0A9-E50E24DCCA9E"

    // Standard battery service
    const val BATTERY_SERVICE_UUID = "0000180F-0000-1000-8000-00805F9B34FB"
    const val BATTERY_LEVEL_UUID   = "00002A19-0000-1000-8000-00805F9B34FB"

    // Device advertises with these name prefixes (F=front, R=rear)
    val SUPPORTED_NAME_PREFIXES = setOf("F_ICN", "R_ICN")

    // Single-byte ASCII commands, written WITHOUT_RESPONSE to TX_CHARACTERISTIC
    const val CMD_OFF:         Byte = 0x7E // '~'
    const val CMD_SOLID:       Byte = 0x4F // 'O'
    const val CMD_FLASH:       Byte = 0x49 // 'I'  — cycling flash
    const val CMD_FLASH_BURST: Byte = 0x3A // ':'
    const val CMD_FLASH_TWIN:  Byte = 0x2C // ','
    const val CMD_FLASH_PULSE: Byte = 0x3B // ';'

    fun buildCommandForMode(modeId: String): ByteArray? = when (modeId) {
        "OFF"         -> byteArrayOf(CMD_OFF)
        "SOLID"       -> byteArrayOf(CMD_SOLID)
        "FLASH"       -> byteArrayOf(CMD_FLASH)
        "FLASH_BURST" -> byteArrayOf(CMD_FLASH_BURST)
        "FLASH_TWIN"  -> byteArrayOf(CMD_FLASH_TWIN)
        "FLASH_PULSE" -> byteArrayOf(CMD_FLASH_PULSE)
        // Radar alert — use cycling flash
        "FAST_FLASH"  -> byteArrayOf(CMD_FLASH)
        else -> null
    }

    val MODES: List<LightModeOption> = listOf(
        LightModeOption("OFF",         "Off"),
        LightModeOption("FLASH",       "Flash"),
        LightModeOption("FLASH_BURST", "Flash Burst"),
        LightModeOption("FLASH_TWIN",  "Flash Twin"),
        LightModeOption("FLASH_PULSE", "Flash Pulse"),
        LightModeOption("SOLID",       "Solid"),
    )

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

class SeeSenseModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = SeeSenseProtocol.MODES
}
