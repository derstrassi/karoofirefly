package io.github.derstrassi.karoofirefly.ble

import android.content.Context
import io.github.derstrassi.karoofirefly.DiscoveredLight
import io.github.derstrassi.karoofirefly.data.LightProtocol
import io.github.derstrassi.karoofirefly.light.LightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MagicshineBleController(context: Context) : LightController {

    companion object {
        private const val TAG = "MagicshineBle"
        private const val WRITE_RETRIES = 5
        private const val WRITE_RETRY_DELAY_MS = 60L
        private const val RECONNECT_MIN_MS = 10_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }
    private val writeMutex = Mutex()

    private val targetService = Uuid.parse(MagicshineProtocol.SERVICE_UUID)
    private val targetChar = Uuid.parse(MagicshineProtocol.CHARACTERISTIC_UUID)

    private data class BleDevice(val peripheral: Peripheral, val name: String)

    private val devices = java.util.concurrent.ConcurrentHashMap<String, BleDevice>()
    private val characteristics = java.util.concurrent.ConcurrentHashMap<String, RemoteCharacteristic>()
    private val deviceConfigs = java.util.concurrent.ConcurrentHashMap<String, MagicshineDeviceConfig>()
    private val batteryLevels = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val temperatures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    var onDeviceConnected: (() -> Unit)? = null

    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private val connectionJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val bleScanner: android.bluetooth.le.BluetoothLeScanner?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    var assignedDeviceIds: Set<String> = emptySet()

    fun startDiscovery() {
        if (scanCallback != null) return
        val scanner = bleScanner ?: run {
            Timber.w("$TAG: No BLE scanner available")
            return
        }
        Timber.d("$TAG: Starting BLE discovery")
        // Raw Android scan: read only the advertised device name. This avoids the Nordic
        // library's advertisement parser, which crashes on malformed 128-bit UUID data.
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                try {
                    val name = result.scanRecord?.deviceName ?: return
                    val address = result.device?.address ?: return
                    if (devices.containsKey(address)) return
                    if (MagicshineProtocol.SUPPORTED_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }) {
                        Timber.d("$TAG: Found Magicshine: $name ($address)")
                        scope.launch { registerFoundDevice(address, name) }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Skipping scan result")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("$TAG: BLE scan failed: $errorCode")
            }
        }
        try {
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start BLE scan")
        }
    }

    private fun registerFoundDevice(address: String, name: String) {
        if (devices.containsKey(address)) return
        val peripheral = centralManager.getPeripheralsById(listOf(address)).firstOrNull() ?: run {
            Timber.w("$TAG: No peripheral for $address")
            return
        }
        devices[address] = BleDevice(peripheral, name)
        deviceConfigs[address] = MagicshineDeviceConfig.forDevice(name)
        Timber.d("$TAG: Device config for $name: module=${deviceConfigs[address]?.moduleType}")
        updateDiscoveredLights()
        if (address in assignedDeviceIds) {
            startConnectionSupervisor(address)
        }
    }

    fun connect(address: String) {
        if (characteristics.containsKey(address)) return
        startConnectionSupervisor(address)
    }

    fun stopDiscovery() {
        scanCallback?.let { cb ->
            try {
                bleScanner?.stopScan(cb)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to stop BLE scan")
            }
        }
        scanCallback = null
    }

    /**
     * Keeps an assigned light connected: retries as long as it is assigned and not
     * connected, so a light that drops (out of range / powered off) reconnects by
     * itself once it is reachable again — without needing the scanner to be running.
     */
    private fun startConnectionSupervisor(address: String) {
        if (connectionJobs[address]?.isActive == true) return
        connectionJobs[address] = scope.launch {
            var backoff = RECONNECT_MIN_MS
            while (isActive && address in assignedDeviceIds) {
                val wasConnected = attemptConnect(address)
                backoff = if (wasConnected) RECONNECT_MIN_MS else minOf(backoff * 2, RECONNECT_MAX_MS)
                delay(backoff)
            }
        }
    }

    /**
     * One connection lifecycle. Returns true if it was connected (then it blocks until the
     * light disconnects and the supervisor retries quickly), false if the attempt failed
     * (the supervisor backs off before the next try).
     */
    private suspend fun attemptConnect(address: String): Boolean {
        val peripheral = devices[address]?.peripheral
            ?: centralManager.getPeripheralsById(listOf(address)).firstOrNull()
            ?: return false
        return try {
            Timber.d("$TAG: Connecting to $address")
            val options = CentralManager.ConnectionOptions.Direct(
                timeout = 8.seconds,
                retry = 2,
                retryDelay = 1.seconds,
            )
            centralManager.connect(peripheral, options)

            val connected = withTimeoutOrNull(10_000) {
                peripheral.state.first { it is ConnectionState.Connected }
                true
            } ?: false
            if (!connected) {
                Timber.w("$TAG: Connection timeout for $address")
                return false
            }

            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic == null) {
                Timber.w("$TAG: Characteristic not found for $address")
                return false
            }
            characteristics[address] = characteristic
            Timber.d("$TAG: Connected to $address, characteristic found")

            scope.launch {
                try {
                    characteristic.subscribe().collect { data -> parseNotification(address, data) }
                } catch (_: Exception) { }
            }

            delay(180)
            writeBytes(address, MagicshineProtocol.buildQuery(0xA4.toByte()))
            delay(100)
            writeBytes(address, MagicshineProtocol.buildQuery(0xA1.toByte()))

            updateDiscoveredLights()
            onDeviceConnected?.invoke()

            scope.launch {
                while (characteristics.containsKey(address)) {
                    delay(60_000)
                    if (!characteristics.containsKey(address)) break
                    writeBytes(address, MagicshineProtocol.buildQuery(0xA4.toByte()))
                    delay(100)
                    writeBytes(address, MagicshineProtocol.buildQuery(0xA1.toByte()))
                }
            }

            // Stay here until the light disconnects, then let the supervisor reconnect.
            peripheral.state.first { it is ConnectionState.Disconnected }
            Timber.d("$TAG: Disconnected from $address")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connect attempt failed for $address")
            false
        } finally {
            characteristics.remove(address)
            updateDiscoveredLights()
        }
    }

    private suspend fun findTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        for (attempt in 1..10) {
            try {
                val services = peripheral.services().value
                val service = services.firstOrNull { it.uuid == targetService }
                val characteristic = service?.characteristics?.firstOrNull { it.uuid == targetChar }
                if (characteristic != null) return characteristic
            } catch (_: Exception) { }
            delay(360)
        }
        return null
    }

    override fun setMode(deviceId: String, modeName: String) {
        val config = deviceConfigs[deviceId]
        if (config == null) {
            Timber.w("$TAG: No config for device $deviceId")
            return
        }
        val command = config.buildCommand(modeName)
        if (command == null) {
            Timber.w("$TAG: Unknown mode: $modeName for device $deviceId")
            return
        }
        Timber.d("$TAG: setMode($deviceId, $modeName) -> ${MagicshineProtocol.bytesToHex(command)}")
        scope.launch {
            writeBytes(deviceId, command)
        }
    }

    fun getDeviceConfig(deviceId: String): MagicshineDeviceConfig? = deviceConfigs[deviceId]

    private suspend fun writeBytes(address: String, bytes: ByteArray) {
        val characteristic = characteristics[address]
        if (characteristic == null) {
            Timber.w("$TAG: Not connected to $address, cannot send command")
            return
        }

        writeMutex.withLock {
            for (attempt in 1..WRITE_RETRIES) {
                try {
                    characteristic.write(bytes, WriteType.WITH_RESPONSE)
                    Timber.d("$TAG: Command sent to $address (${bytes.size} bytes)")
                    return
                } catch (e: Exception) {
                    if (attempt == WRITE_RETRIES) {
                        Timber.e(e, "$TAG: Write failed after $WRITE_RETRIES attempts to $address")
                    } else {
                        delay(WRITE_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun updateDiscoveredLights() {
        _discoveredLights.value = devices.map { (address, device) ->
            DiscoveredLight(
                id = address,
                name = device.name,
                manufacturer = "Magicshine",
                protocol = LightProtocol.BLE,
                connected = characteristics.containsKey(address),
                batteryPercent = batteryLevels[address],
                temperature = temperatures[address],
            )
        }
    }

    private fun parseNotification(address: String, data: ByteArray) {
        if (data.size < 6) return
        Timber.d("$TAG: Notification from $address: ${MagicshineProtocol.bytesToHex(data)}")
        val type = data[2].toInt() and 0xFF
        val content = if (data.size > 6) data.sliceArray(4 until data.size - 2) else byteArrayOf()

        when (type) {
            0xB4 -> {
                // Battery: content[4]
                if (content.size >= 5) {
                    val battery = content[4].toInt() and 0xFF
                    if (battery in 0..100) {
                        batteryLevels[address] = battery
                        Timber.d("$TAG: Battery $address: $battery%")
                    }
                    updateDiscoveredLights()
                }
            }
            0xB1 -> {
                var temp: Int? = null
                // Try marker "1703" first (EVO 1700 etc.)
                val hex = MagicshineProtocol.bytesToHex(data)
                val markerIndex = hex.indexOf("1703")
                if (markerIndex != -1 && hex.length >= markerIndex + 6) {
                    temp = hex.substring(markerIndex + 4, markerIndex + 6).toIntOrNull(16)
                }
                // Fallback: content[4] with sign at content[5] (Hori 1300)
                if (temp == null && content.size >= 6) {
                    val rawTemp = content[4].toInt() and 0xFF
                    val sign = content[5].toInt() and 0xFF
                    temp = if (sign == 0 && rawTemp > 0) -rawTemp else rawTemp
                }
                if (temp != null && temp in -40..120) {
                    temperatures[address] = temp
                    Timber.d("$TAG: Temperature $address: ${temp}°C")
                    updateDiscoveredLights()
                }
            }
        }
    }

    fun disconnect(address: String) {
        Timber.d("$TAG: Disconnecting $address")
        connectionJobs[address]?.cancel()
        connectionJobs.remove(address)
        characteristics.remove(address)
        batteryLevels.remove(address)
        temperatures.remove(address)
        updateDiscoveredLights()
    }

    fun allConnected(deviceIds: Set<String>): Boolean =
        deviceIds.all { characteristics.containsKey(it) }

    fun destroy() {
        stopDiscovery()
        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()
        characteristics.clear()
        devices.clear()
        scope.cancel()
    }
}
