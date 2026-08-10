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
import io.github.derstrassi.karoofirefly.ble.SeeSenseBleController
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
    internal lateinit var seeSenseController: SeeSenseBleController
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
    @Volatile private var settingsUiActive = false
    @Volatile private var rideActive = false

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
        seeSenseController = SeeSenseBleController(applicationContext)
        lightControllers[LightProtocol.ANT_PLUS] = lightControl
        lightControllers[LightProtocol.BLE] = magicshineController
        lightControllers[LightProtocol.SEE_SENSE] = seeSenseController
        val onBleDeviceConnected: () -> Unit = {
            stopBleIfNotNeeded()
            engine.activeZone.value?.let { zone -> engine.onApplyZone?.invoke(zone) }
        }
        magicshineController.onDeviceConnected = onBleDeviceConnected
        seeSenseController.onDeviceConnected = onBleDeviceConnected
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
            if (engine.settings.zoneNotificationsEnabled && engine.state.value != LightControlEngine.EngineState.IDLE && engine.settings.lightAssignments.isNotEmpty()) {
                playNotificationSound()
                karooSystem.dispatch(
                    InRideAlert(
                        id = "zone-change",
                        icon = R.drawable.ic_firefly,
                        title = "$oldZone → $newZone ($reason)",
                        detail = buildModeDetail(newZone),
                        autoDismissMs = 30000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
            }
        }

        // Merge ANT+, Magicshine BLE, and See.Sense BLE discovered lights
        extensionScope.launch {
            combine(
                _antLights,
                magicshineController.discoveredLights,
                seeSenseController.discoveredLights,
                lightControl.connectionStates,
            ) { ant, magicshine, seeSense, connStates ->
                ant.map { it.copy(connected = connStates[it.id] == "CONNECTED") } + magicshine + seeSense
            }.collect { merged ->
                _discoveredLights.value = merged
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
                engine.onRideStart()
                startDiscoveryPolling()
                updateRadarMonitoring()
            }
            is RideState.Paused -> engine.onRidePause()
            is RideState.Idle -> {
                rideActive = false
                stopDiscoveryPolling()
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
        seeSenseController.assignedDeviceIds = engine.settings.lightAssignments
            .filter { it.protocol == LightProtocol.SEE_SENSE }
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
        for (id in magicshineController.assignedDeviceIds) magicshineController.connect(id)
        for (id in seeSenseController.assignedDeviceIds) seeSenseController.connect(id)
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
        val msAssigned = magicshineController.assignedDeviceIds
        val ssAssigned = seeSenseController.assignedDeviceIds
        return (msAssigned.isEmpty() || magicshineController.allConnected(msAssigned)) &&
               (ssAssigned.isEmpty() || seeSenseController.allConnected(ssAssigned))
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

    private fun hasBleAssignments(): Boolean =
        engine.settings.lightAssignments.any {
            it.protocol == LightProtocol.BLE || it.protocol == LightProtocol.SEE_SENSE
        }

    private fun startBleIfNeeded() {
        Timber.d("$TAG: startBleIfNeeded: settingsUiActive=$settingsUiActive, hasBleAssignments=${hasBleAssignments()}")
        if (settingsUiActive || hasBleAssignments()) {
            bleStartJob?.cancel()
            bleStartJob = extensionScope.launch {
                kotlinx.coroutines.delay(2000)
                Timber.d("$TAG: Requesting Bluetooth and starting BLE discovery")
                karooSystem.dispatch(RequestBluetooth(extension))
                magicshineController.startDiscovery()
                seeSenseController.startDiscovery()
            }
        }
    }

    private fun stopBleIfNotNeeded() {
        if (settingsUiActive) return
        if (!hasBleAssignments()) {
            magicshineController.stopDiscovery()
            seeSenseController.stopDiscovery()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        } else {
            if (magicshineController.allConnected(magicshineController.assignedDeviceIds)) {
                magicshineController.stopDiscovery()
            }
            if (seeSenseController.allConnected(seeSenseController.assignedDeviceIds)) {
                seeSenseController.stopDiscovery()
            }
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
        seeSenseController.destroy()
        lightControl.unbind()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
