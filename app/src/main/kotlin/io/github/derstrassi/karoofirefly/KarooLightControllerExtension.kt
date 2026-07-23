package io.github.derstrassi.karoofirefly

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.github.derstrassi.karoofirefly.ble.MagicshineBleController
import io.github.derstrassi.karoofirefly.karoo.KarooLightControl
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import io.github.derstrassi.karoofirefly.data.LightProtocol
import io.github.derstrassi.karoofirefly.data.LightRole
import io.github.derstrassi.karoofirefly.data.modeProviderFor
import io.github.derstrassi.karoofirefly.data.PreferencesRepository
import io.github.derstrassi.karoofirefly.light.LightController
import io.github.derstrassi.karoofirefly.datatypes.LightStatusDataType
import io.github.derstrassi.karoofirefly.engine.AmbientLightSensor
import io.github.derstrassi.karoofirefly.engine.LightControlEngine
import io.github.derstrassi.karoofirefly.engine.TimeBasedController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class DiscoveredLight(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val protocol: LightProtocol = LightProtocol.ANT_PLUS,
    val connected: Boolean = true,
    val batteryPercent: Int? = null,
    val temperature: Int? = null,
)

class KarooLightControllerExtension : KarooExtension("karoo-light-controller", BuildConfig.VERSION_NAME) {

    companion object {
        const val TAG = "LightController"
        private const val BIKE_LIGHT_DATA_TYPE = "TYPE_BIKE_LIGHT_ID"
        private const val DEVICE_TYPE_BIKE_LIGHT = 35
        private const val CONNECTION_ALERT_COOLDOWN_MS = 60_000L
        private const val BATTERY_CHECK_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: KarooLightControllerExtension? = null
        fun getInstance(): KarooLightControllerExtension? = instance
    }

    init {
        instance = this
    }

    internal lateinit var karooSystem: KarooSystemService
    internal lateinit var lightControl: KarooLightControl
    internal lateinit var magicshineController: MagicshineBleController
    private val lightControllers = mutableMapOf<LightProtocol, LightController>()
    internal lateinit var timeController: TimeBasedController
    internal lateinit var ambientLightSensor: AmbientLightSensor
    internal lateinit var engine: LightControlEngine
    internal lateinit var repository: PreferencesRepository

    private val _antLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    private var savedDevicesConsumerId: String? = null
    private var radarConsumerId: String? = null
    @Volatile private var radarThreatActive = false
    private var bleStartJob: kotlinx.coroutines.Job? = null
    private var discoveryPollingJob: kotlinx.coroutines.Job? = null
    private var batteryMonitorJob: kotlinx.coroutines.Job? = null
    @Volatile private var settingsUiActive = false
    @Volatile private var rideActive = false

    private val batteryAlerted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val connectionAlertLastTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val antConnPrevState = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types by lazy {
        listOf(LightStatusDataType(engine))
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("$TAG: Extension onCreate")

        karooSystem = KarooSystemService(applicationContext)
        repository = PreferencesRepository(applicationContext)
        lightControl = KarooLightControl(applicationContext)
        magicshineController = MagicshineBleController(applicationContext)
        lightControllers[LightProtocol.ANT_PLUS] = lightControl
        lightControllers[LightProtocol.BLE] = magicshineController
        magicshineController.onDeviceConnected = {
            stopBleIfNotNeeded()
            engine.activeZone.value?.let { zone -> engine.onApplyZone?.invoke(zone) }
        }
        magicshineController.onDeviceDisconnected = { deviceId -> maybeAlertConnectionLost(deviceId) }
        timeController = TimeBasedController()
        ambientLightSensor = AmbientLightSensor(applicationContext)
        engine = LightControlEngine(timeController, ambientLightSensor)

        engine.onApplyZone = { zone ->
            for (assignment in engine.settings.lightAssignments) {
                val modeName = if (zone == null) "OFF" else assignment.modeForZone(zone)
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, modeName)
            }
            updateRadarMonitoring()
        }

        engine.onZoneChange = { oldZone, newZone, reason ->
            if (engine.state.value != LightControlEngine.EngineState.IDLE && engine.settings.lightAssignments.isNotEmpty()) {
                if (engine.settings.zoneNotifySound) playNotificationSound()
                if (engine.settings.zoneNotifyPopup) {
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "zone-change",
                            icon = R.drawable.ic_firefly,
                            title = "$oldZone → $newZone ($reason)",
                            detail = buildModeDetail(newZone),
                            autoDismissMs = engine.settings.popupDurationSeconds * 1000L,
                            backgroundColor = android.R.color.black,
                            textColor = android.R.color.white,
                        ),
                    )
                }
            }
        }

        // Merge ANT+ and BLE discovered lights
        extensionScope.launch {
            combine(_antLights, magicshineController.discoveredLights, lightControl.connectionStates) { ant, ble, connStates ->
                ant.map { it.copy(connected = connStates[it.id] == "CONNECTED") } + ble
            }.collect { merged ->
                _discoveredLights.value = merged
            }
        }

        // Detect ANT+ lights dropping from CONNECTED to a lost state during a ride
        extensionScope.launch {
            lightControl.connectionStates.collect { states ->
                for ((deviceId, state) in states) {
                    val prev = antConnPrevState.put(deviceId, state)
                    if (prev == "CONNECTED" && state != "CONNECTED") {
                        maybeAlertConnectionLost(deviceId)
                    }
                }
            }
        }

        karooSystem.connect { connected ->
            Timber.d("$TAG: Karoo system connected=$connected")
            if (connected) {
                lightControl.bind()
                setupConsumers()
                loadSettings()
                discoverKarooLights()
            }
        }
    }

    private fun setupConsumers() {
        karooSystem.addConsumer<OnLocationChanged> { event ->
            timeController.onLocationUpdate(event.lat, event.lng)
        }

        karooSystem.addConsumer<RideState> { state ->
            handleRideState(state)
        }

    }

    private fun handleRideState(state: RideState) {
        when (state) {
            is RideState.Recording -> {
                rideActive = true
                batteryAlerted.clear()
                connectionAlertLastTime.clear()
                engine.onRideStart()
                startDiscoveryPolling()
                startBatteryMonitor()
                updateRadarMonitoring()
            }
            is RideState.Paused -> engine.onRidePause()
            is RideState.Idle -> {
                rideActive = false
                stopDiscoveryPolling()
                stopBatteryMonitor()
                stopRadarMonitoring()
                engine.onRideStop()
            }
        }
    }

    private fun loadSettings() {
        extensionScope.launch {
            var settings = repository.settingsFlow.first()
            val migrated = settings.migrateProfilesToAssignments()
            if (migrated != settings) {
                repository.updateSettings(migrated)
                settings = migrated
            }
            engine.settings = settings
            timeController.dawnOffsetMinutes = settings.dawnOffsetMinutes
            timeController.duskOffsetMinutes = settings.duskOffsetMinutes
            engine.updateAmbientSensor()
            syncBleAssignments()
            startBleIfNeeded()
        }
    }

    private fun syncBleAssignments() {
        magicshineController.assignedDeviceIds = engine.settings.lightAssignments
            .filter { it.protocol == LightProtocol.BLE }
            .map { it.deviceId }
            .toSet()
    }

    fun testMode(deviceId: String, modeName: String) {
        val assignment = engine.settings.lightAssignments.find { it.deviceId == deviceId } ?: return
        val effectiveMode = modeName
        extensionScope.launch {
            lightControllers[assignment.protocol]?.setMode(deviceId, effectiveMode)
            kotlinx.coroutines.delay(3000)
            lightControllers[assignment.protocol]?.setMode(deviceId, "OFF")
        }
    }

    fun onAssignmentChanged() {
        syncBleAssignments()
        // Connect newly assigned BLE lights
        for (id in magicshineController.assignedDeviceIds) {
            magicshineController.connect(id)
        }
        startBleIfNeeded()
        updateRadarMonitoring()
    }

    fun setSettingsUiActive(active: Boolean) {
        Timber.d("$TAG: setSettingsUiActive=$active")
        settingsUiActive = active
        if (active) {
            startDiscoveryPolling()
            startBleIfNeeded()
        } else {
            if (!rideActive) stopDiscoveryPolling()
            stopBleIfNotNeeded()
        }
    }

    private fun allAssignedBleConnected(): Boolean {
        val bleAssigned = magicshineController.assignedDeviceIds
        return bleAssigned.isEmpty() || magicshineController.allConnected(bleAssigned)
    }

    private fun startDiscoveryPolling() {
        if (discoveryPollingJob != null) return
        discoveryPollingJob = extensionScope.launch {
            while (true) {
                discoverKarooLights()
                if (allAssignedBleConnected()) {
                    Timber.d("$TAG: All assigned lights connected, stopping discovery polling")
                    break
                }
                kotlinx.coroutines.delay(10_000)
            }
            discoveryPollingJob = null
        }
    }

    private fun stopDiscoveryPolling() {
        discoveryPollingJob?.cancel()
        discoveryPollingJob = null
    }

    private fun startBatteryMonitor() {
        if (batteryMonitorJob != null) return
        batteryMonitorJob = extensionScope.launch {
            while (true) {
                kotlinx.coroutines.delay(BATTERY_CHECK_INTERVAL_MS)
                if (!rideActive) continue
                discoverKarooLights() // refresh ANT+ battery from Karoo's SavedDevices
                checkBatteryLevels()
            }
        }
    }

    private fun stopBatteryMonitor() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = null
    }

    private fun checkBatteryLevels() {
        if (!engine.settings.batteryNotifySound && !engine.settings.batteryNotifyPopup) return
        val threshold = engine.settings.batteryAlertThreshold
        val notifyIds = engine.settings.lightAssignments
            .filter { it.notificationsActive }.map { it.deviceId }.toSet()
        for (light in discoveredLights.value) {
            if (light.id !in notifyIds) continue
            val pct = light.batteryPercent ?: continue
            if (pct <= threshold && batteryAlerted.add(light.id)) {
                Timber.d("$TAG: Battery Low alert for ${light.name} ($pct%)")
                if (engine.settings.batteryNotifyPopup) {
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "battery-low-${light.id}",
                            icon = R.drawable.ic_firefly,
                            title = "Battery Low",
                            detail = "${light.name}: $pct%",
                            autoDismissMs = engine.settings.popupDurationSeconds * 1000L,
                            backgroundColor = android.R.color.holo_red_dark,
                            textColor = android.R.color.white,
                        ),
                    )
                }
                if (engine.settings.batteryNotifySound) playWarningSound()
            }
        }
    }

    private fun maybeAlertConnectionLost(deviceId: String) {
        if (!engine.settings.connectionNotifySound && !engine.settings.connectionNotifyPopup) return
        if (!rideActive) return
        val assignment = engine.settings.lightAssignments.find { it.deviceId == deviceId } ?: return
        if (!assignment.notificationsActive) return
        val now = System.currentTimeMillis()
        val last = connectionAlertLastTime[deviceId]
        if (last != null && now - last < CONNECTION_ALERT_COOLDOWN_MS) return
        connectionAlertLastTime[deviceId] = now
        Timber.d("$TAG: Light Disconnected alert for ${assignment.deviceName}")
        if (engine.settings.connectionNotifyPopup) {
            karooSystem.dispatch(
                InRideAlert(
                    id = "conn-lost-$deviceId",
                    icon = R.drawable.ic_firefly,
                    title = "Light Disconnected",
                    detail = assignment.deviceName,
                    autoDismissMs = engine.settings.popupDurationSeconds * 1000L,
                    backgroundColor = android.R.color.holo_orange_dark,
                    textColor = android.R.color.white,
                ),
            )
        }
        if (engine.settings.connectionNotifySound) playWarningSound()
    }

    private fun hasBleAssignments(): Boolean =
        engine.settings.lightAssignments.any { it.protocol == LightProtocol.BLE }

    private fun startBleIfNeeded() {
        Timber.d("$TAG: startBleIfNeeded: settingsUiActive=$settingsUiActive, hasBleAssignments=${hasBleAssignments()}")
        if (settingsUiActive || hasBleAssignments()) {
            bleStartJob?.cancel()
            bleStartJob = extensionScope.launch {
                kotlinx.coroutines.delay(2000)
                Timber.d("$TAG: Requesting Bluetooth and starting BLE discovery")
                karooSystem.dispatch(RequestBluetooth(extension))
                magicshineController.startDiscovery()
            }
        }
    }

    private fun stopBleIfNotNeeded() {
        if (settingsUiActive) return
        if (!hasBleAssignments()) {
            magicshineController.stopDiscovery()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        } else if (magicshineController.allConnected(magicshineController.assignedDeviceIds)) {
            magicshineController.stopDiscovery()
        }
    }

    internal fun discoverKarooLights() {
        savedDevicesConsumerId?.let { karooSystem.removeConsumer(it) }
        extensionScope.launch {
            Timber.d("$TAG: Querying Karoo for saved bike light devices")
            savedDevicesConsumerId = karooSystem.addConsumer<SavedDevices> { savedDevices ->
                val lights = savedDevices.devices.filter { device ->
                    device.supportedDataTypes.contains(BIKE_LIGHT_DATA_TYPE) && device.enabled
                }.filter { device ->
                    val parts = device.id.split("-")
                    parts.size >= 3 && parts[1].toIntOrNull() == DEVICE_TYPE_BIKE_LIGHT
                }

                antDeviceCache = lights.map { device ->
                    val batteryText = device.details?.lastBattery?.name
                    val batteryPercent = when (batteryText) {
                        "GOOD" -> 80
                        "OK" -> 50
                        "LOW" -> 20
                        "CRITICAL" -> 5
                        else -> null
                    }
                    AntDeviceInfo(device.id, device.name, device.details?.manufacturer, batteryPercent)
                }
                updateAntLights()

                Timber.d("$TAG: Found ${antDeviceCache.size} ANT+ bike light(s): ${antDeviceCache.joinToString { "${it.name} (${it.id})" }}")

                for (device in antDeviceCache) {
                    lightControl.registerConnectionState(device.id)
                    lightControl.registerForLightParameters(device.id)
                }
            }
        }
    }

    private data class AntDeviceInfo(val id: String, val name: String, val manufacturer: String?, val batteryPercent: Int?)
    private var antDeviceCache = listOf<AntDeviceInfo>()

    private fun updateAntLights() {
        _antLights.value = antDeviceCache.map { device ->
            DiscoveredLight(
                id = device.id,
                name = device.name,
                manufacturer = device.manufacturer,
                connected = lightControl.connectionStates.value[device.id] == "CONNECTED",
                batteryPercent = device.batteryPercent,
            )
        }
    }

    private fun buildModeDetail(zone: DayTimeZone?): String {
        if (zone == null) return "Lights Off"
        return engine.settings.lightAssignments.joinToString("\n") {
            val roleLabel = when (it.role) {
                LightRole.FRONT -> "F"
                LightRole.REAR -> "R"
            }
            val modeId = it.modeForZone(zone)
            val displayName = modeProviderFor(it.protocol, it.deviceId)
                .availableModes()
                .find { m -> m.id == modeId }?.displayName ?: modeId
            "$roleLabel: $displayName"
        }
    }

    override fun onBonusAction(actionId: String) {
        Timber.d("$TAG: BonusAction $actionId")
        when (actionId) {
            "toggle-lights" -> {
                engine.onToggleLights()
                karooSystem.dispatch(
                    InRideAlert(
                        id = "light-toggle",
                        icon = R.drawable.ic_firefly,
                        title = "Lights Toggled",
                        detail = buildModeDetail(engine.activeZone.value),
                        autoDismissMs = 3000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
            }
            "cycle-mode" -> {
                engine.onCycleMode()
                karooSystem.dispatch(
                    InRideAlert(
                        id = "light-mode",
                        icon = R.drawable.ic_firefly,
                        title = "Light Mode Changed",
                        detail = buildModeDetail(engine.activeZone.value),
                        autoDismissMs = 3000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
            }
        }
    }

    private fun playNotificationSound() {
        karooSystem.dispatch(
            PlayBeepPattern(
                listOf(
                    PlayBeepPattern.Tone(frequency = 784, durationMs = 120),
                    PlayBeepPattern.Tone(frequency = null, durationMs = 60),
                    PlayBeepPattern.Tone(frequency = 988, durationMs = 120),
                    PlayBeepPattern.Tone(frequency = null, durationMs = 60),
                    PlayBeepPattern.Tone(frequency = 1319, durationMs = 250),
                ),
            ),
        )
    }

    private fun playWarningSound() {
        karooSystem.dispatch(
            PlayBeepPattern(
                listOf(
                    PlayBeepPattern.Tone(frequency = 880, durationMs = 150),
                    PlayBeepPattern.Tone(frequency = null, durationMs = 60),
                    PlayBeepPattern.Tone(frequency = 660, durationMs = 150),
                    PlayBeepPattern.Tone(frequency = null, durationMs = 60),
                    PlayBeepPattern.Tone(frequency = 440, durationMs = 300),
                ),
            ),
        )
    }

    private fun updateRadarMonitoring() {
        val needsRadar = engine.settings.lightAssignments.any { it.radarWarnFlash }
        if (needsRadar) startRadarMonitoring() else stopRadarMonitoring()
    }

    private fun startRadarMonitoring() {
        if (radarConsumerId != null) return
        Timber.d("$TAG: Starting radar monitoring")
        radarConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.RADAR),
        ) { event: OnStreamState ->
            if (event.state is StreamState.Streaming) {
                val values = (event.state as StreamState.Streaming).dataPoint.values
                val threat = values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
                onRadarThreat(threat > 0)
            }
        }
    }

    private fun stopRadarMonitoring() {
        radarConsumerId?.let {
            Timber.d("$TAG: Stopping radar monitoring")
            karooSystem.removeConsumer(it)
        }
        radarConsumerId = null
        radarThreatActive = false
    }

    private fun onRadarThreat(threatDetected: Boolean) {
        if (threatDetected == radarThreatActive) return
        radarThreatActive = threatDetected
        Timber.d("$TAG: Radar threat=${if (threatDetected) "DETECTED" else "CLEAR"}")

        for (assignment in engine.settings.lightAssignments) {
            if (!assignment.radarWarnFlash) continue
            val zone = engine.activeZone.value
            val currentMode = if (zone == null) "OFF" else assignment.modeForZone(zone)
            if (currentMode != "OFF") continue

            val modeName = if (threatDetected) "FAST_FLASH" else "OFF"
            lightControllers[assignment.protocol]?.setMode(assignment.deviceId, modeName)
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: Extension onDestroy")
        instance = null
        stopRadarMonitoring()
        engine.destroy()
        magicshineController.destroy()
        lightControl.unbind()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
