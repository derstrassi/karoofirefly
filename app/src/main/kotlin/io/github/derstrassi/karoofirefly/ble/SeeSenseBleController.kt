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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SeeSenseBleController(context: Context) : LightController {

    companion object {
        private const val TAG = "SeeSenseBle"
        private const val WRITE_RETRIES = 3
        private const val WRITE_RETRY_DELAY_MS = 60L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.Factory.native(appContext, scope) }
    private val writeMutex = Mutex()

    // Commands go to Nordic UART TX
    private val uartServiceUuid  = Uuid.parse(SeeSenseProtocol.UART_SERVICE_UUID)
    private val txCharUuid       = Uuid.parse(SeeSenseProtocol.TX_CHARACTERISTIC_UUID)
    // Status notifications come from the status service
    private val statusServiceUuid = Uuid.parse(SeeSenseProtocol.STATUS_SERVICE_UUID)
    private val statusCharUuid    = Uuid.parse(SeeSenseProtocol.STATUS_CHARACTERISTIC_UUID)
    // Battery
    private val batteryServiceUuid = Uuid.parse(SeeSenseProtocol.BATTERY_SERVICE_UUID)
    private val batteryCharUuid    = Uuid.parse(SeeSenseProtocol.BATTERY_LEVEL_UUID)

    private data class BleDevice(val peripheral: Peripheral, val name: String)

    private val devices       = java.util.concurrent.ConcurrentHashMap<String, BleDevice>()
    private val txChars       = java.util.concurrent.ConcurrentHashMap<String, RemoteCharacteristic>()
    private val batteryLevels = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    var onDeviceConnected: (() -> Unit)? = null

    private var scanJob: Job? = null
    private val connectionJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    var assignedDeviceIds: Set<String> = emptySet()

    // ── Discovery ──────────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (scanJob != null) return
        Timber.d("$TAG: Starting BLE discovery for See.Sense lights")
        scanJob = scope.launch {
            try {
                centralManager.scan { }
                    .catch { e -> Timber.e(e, "$TAG: scan flow error") }
                    .collect { result ->
                        try {
                            val name = result.advertisingData.name ?: return@collect
                            val address = result.peripheral.address
                            if (SeeSenseProtocol.SUPPORTED_NAME_PREFIXES.any {
                                    name.startsWith(it, ignoreCase = true)
                                }
                            ) {
                                if (!devices.containsKey(address)) {
                                    Timber.d("$TAG: Found See.Sense: $name ($address)")
                                    devices[address] = BleDevice(result.peripheral, name)
                                    updateDiscoveredLights()
                                    if (address in assignedDeviceIds) {
                                        connectToPeripheral(address, result.peripheral)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "$TAG: Skipping malformed scan result")
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: scan error")
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connect(address: String) {
        val device = devices[address] ?: return
        if (txChars.containsKey(address)) return
        connectToPeripheral(address, device.peripheral)
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    private fun connectToPeripheral(address: String, peripheral: Peripheral) {
        connectionJobs[address]?.cancel()
        connectionJobs[address] = scope.launch {
            try {
                Timber.d("$TAG: Connecting to $address")
                val options = CentralManager.ConnectionOptions.Direct(
                    timeout = 8.seconds,
                    retry = 2,
                    retryDelay = 1.seconds,
                )
                centralManager.connect(peripheral, options)

                var connected = false
                withTimeoutOrNull(10_000) {
                    peripheral.state.collect { state ->
                        if (state is ConnectionState.Connected) {
                            connected = true
                            return@collect
                        }
                    }
                }
                if (!connected) {
                    Timber.w("$TAG: Connection timeout for $address")
                    return@launch
                }

                // Discover characteristics with retries (service discovery can be slow)
                var txChar: RemoteCharacteristic? = null
                var statusChar: RemoteCharacteristic? = null
                var batteryChar: RemoteCharacteristic? = null
                for (attempt in 1..10) {
                    try {
                        val services = peripheral.services().value
                        val uartSvc   = services.firstOrNull { it.uuid == uartServiceUuid }
                        val statusSvc = services.firstOrNull { it.uuid == statusServiceUuid }
                        val battSvc   = services.firstOrNull { it.uuid == batteryServiceUuid }
                        txChar      = uartSvc?.characteristics?.firstOrNull { it.uuid == txCharUuid }
                        statusChar  = statusSvc?.characteristics?.firstOrNull { it.uuid == statusCharUuid }
                        batteryChar = battSvc?.characteristics?.firstOrNull { it.uuid == batteryCharUuid }
                        if (txChar != null) break
                    } catch (_: Exception) { }
                    delay(360)
                }

                if (txChar == null) {
                    Timber.w("$TAG: UART TX characteristic not found for $address")
                    return@launch
                }

                txChars[address] = txChar
                Timber.d("$TAG: Connected to $address")

                // Subscribe to status notifications (informational)
                statusChar?.let { char ->
                    scope.launch {
                        try {
                            char.subscribe().collect { data ->
                                Timber.d("$TAG: Status from $address: ${SeeSenseProtocol.bytesToHex(data)}")
                            }
                        } catch (_: Exception) { }
                    }
                }

                // Subscribe to battery level
                batteryChar?.let { char ->
                    scope.launch {
                        try {
                            char.subscribe().collect { data ->
                                if (data.isNotEmpty()) {
                                    val pct = data[0].toInt() and 0xFF
                                    if (pct in 0..100) {
                                        batteryLevels[address] = pct
                                        Timber.d("$TAG: Battery $address: $pct%")
                                        updateDiscoveredLights()
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }

                updateDiscoveredLights()
                onDeviceConnected?.invoke()

                // Wait for disconnection, then reconnect
                peripheral.state.collect { state ->
                    if (state is ConnectionState.Disconnected) {
                        Timber.d("$TAG: Disconnected from $address, reconnecting in 5 s")
                        txChars.remove(address)
                        updateDiscoveredLights()
                        delay(5_000)
                        devices[address]?.let { connectToPeripheral(address, it.peripheral) }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Connection error for $address")
                txChars.remove(address)
            }
        }
    }

    // ── Control ────────────────────────────────────────────────────────────────

    override fun setMode(deviceId: String, modeName: String) {
        val command = SeeSenseProtocol.buildCommandForMode(modeName)
        if (command == null) {
            Timber.w("$TAG: Unknown mode '$modeName' for $deviceId")
            return
        }
        Timber.d("$TAG: setMode($deviceId, $modeName) -> '${command[0].toInt().toChar()}'")
        scope.launch { writeBytes(deviceId, command) }
    }

    private suspend fun writeBytes(address: String, bytes: ByteArray) {
        val char = txChars[address]
        if (char == null) {
            Timber.w("$TAG: Not connected to $address")
            return
        }
        writeMutex.withLock {
            for (attempt in 1..WRITE_RETRIES) {
                try {
                    char.write(bytes, WriteType.WITHOUT_RESPONSE)
                    Timber.d("$TAG: Command sent to $address")
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun updateDiscoveredLights() {
        _discoveredLights.value = devices.map { (address, device) ->
            DiscoveredLight(
                id             = address,
                name           = device.name,
                manufacturer   = "See.Sense",
                protocol       = LightProtocol.SEE_SENSE,
                connected      = txChars.containsKey(address),
                batteryPercent = batteryLevels[address],
            )
        }
    }

    fun allConnected(deviceIds: Set<String>): Boolean =
        deviceIds.all { txChars.containsKey(it) }

    fun disconnect(address: String) {
        Timber.d("$TAG: Disconnecting $address")
        connectionJobs[address]?.cancel()
        connectionJobs.remove(address)
        txChars.remove(address)
        batteryLevels.remove(address)
        updateDiscoveredLights()
    }

    fun destroy() {
        stopDiscovery()
        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()
        txChars.clear()
        devices.clear()
        scope.cancel()
    }
}
