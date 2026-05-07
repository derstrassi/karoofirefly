package io.github.derstrassi.karoofirefly

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.github.derstrassi.karoofirefly.ble.MagicshineBleController
import io.github.derstrassi.karoofirefly.karoo.KarooLightControl
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import io.github.derstrassi.karoofirefly.data.LightProtocol
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
    private val lightControllers = mutableMapOf<LightProtocol, LightController>()
    internal lateinit var timeController: TimeBasedController
    internal lateinit var ambientLightSensor: AmbientLightSensor
    internal lateinit var engine: LightControlEngine
    internal lateinit var repository: PreferencesRepository

    private val _antLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    private var savedDevicesConsumerId: String? = null
    @Volatile private var settingsUiActive = false

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
        timeController = TimeBasedController()
        ambientLightSensor = AmbientLightSensor(applicationContext)
        engine = LightControlEngine(timeController, ambientLightSensor)

        engine.onApplyZone = { zone ->
            for (assignment in engine.settings.lightAssignments) {
                val modeName = if (zone == null) {
                    "OFF"
                } else {
                    when (zone) {
                        DayTimeZone.DAY -> assignment.dayMode
                        DayTimeZone.NIGHT -> assignment.nightMode
                    }
                }
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, modeName)
            }
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
                        autoDismissMs = 10000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
            }
        }

        // Merge ANT+ and BLE discovered lights
        extensionScope.launch {
            combine(_antLights, magicshineController.discoveredLights) { ant, ble ->
                ant + ble
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
                startBleIfNeeded()
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
                engine.onRideStart()
                if (_discoveredLights.value.isEmpty()) {
                    discoverKarooLights()
                }
            }
            is RideState.Paused -> engine.onRidePause()
            is RideState.Idle -> engine.onRideStop()
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
        }
    }

    fun setSettingsUiActive(active: Boolean) {
        settingsUiActive = active
        if (active) startBleIfNeeded() else stopBleIfNotNeeded()
    }

    private fun hasBleAssignments(): Boolean =
        engine.settings.lightAssignments.any { it.protocol == LightProtocol.BLE }

    private fun startBleIfNeeded() {
        if (settingsUiActive || hasBleAssignments()) {
            extensionScope.launch {
                kotlinx.coroutines.delay(2000)
                karooSystem.dispatch(RequestBluetooth(extension))
                magicshineController.startDiscovery()
            }
        }
    }

    private fun stopBleIfNotNeeded() {
        if (!settingsUiActive && !hasBleAssignments()) {
            magicshineController.stopDiscovery()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        } else if (!settingsUiActive && hasBleAssignments()) {
            val assignedBleIds = engine.settings.lightAssignments
                .filter { it.protocol == LightProtocol.BLE }
                .map { it.deviceId }
                .toSet()
            if (magicshineController.allConnected(assignedBleIds)) {
                magicshineController.stopDiscovery()
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

                val discovered = lights.map { DiscoveredLight(it.id, it.name, it.details?.manufacturer) }
                _antLights.value = discovered

                Timber.d("$TAG: Found ${discovered.size} ANT+ bike light(s): ${discovered.joinToString { "${it.name} (${it.id})" }}")
            }
        }
    }

    private fun buildModeDetail(zone: DayTimeZone?): String {
        if (zone == null) return "Lights Off"
        return engine.settings.lightAssignments.joinToString("\n") { assignment ->
            val modeName = when (zone) {
                DayTimeZone.DAY -> assignment.dayMode
                DayTimeZone.NIGHT -> assignment.nightMode
            }
            "${assignment.deviceName}: $modeName"
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

    override fun onDestroy() {
        Timber.d("$TAG: Extension onDestroy")
        instance = null
        engine.destroy()
        magicshineController.destroy()
        lightControl.unbind()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
