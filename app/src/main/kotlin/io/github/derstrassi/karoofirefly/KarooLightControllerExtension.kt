package io.github.derstrassi.karoofirefly

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.github.derstrassi.karoofirefly.karoo.KarooLightControl
import io.github.derstrassi.karoofirefly.data.PreferencesRepository
import io.github.derstrassi.karoofirefly.datatypes.LightStatusDataType
import io.github.derstrassi.karoofirefly.engine.AmbientLightSensor
import io.github.derstrassi.karoofirefly.engine.LightControlEngine
import io.github.derstrassi.karoofirefly.engine.TimeBasedController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

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
    internal lateinit var timeController: TimeBasedController
    internal lateinit var ambientLightSensor: AmbientLightSensor
    internal lateinit var engine: LightControlEngine
    internal lateinit var repository: PreferencesRepository

    @Volatile private var frontLightId: String? = null
    @Volatile private var rearLightId: String? = null
    private var savedDevicesConsumerId: String? = null

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
        timeController = TimeBasedController()
        ambientLightSensor = AmbientLightSensor(applicationContext)
        engine = LightControlEngine(timeController, ambientLightSensor)

        // Wire engine mode changes to Karoo's SensorService
        engine.onSetModes = { frontMode, rearMode ->
            frontLightId?.let { lightControl.setLightMode(it, frontMode) }
            rearLightId?.let { lightControl.setLightMode(it, rearMode) }
        }

        engine.onZoneChange = { oldZone, newZone, reason, frontMode, rearMode ->
            if (engine.settings.zoneNotificationsEnabled && engine.state.value != LightControlEngine.EngineState.IDLE) {
                playNotificationSound()
                karooSystem.dispatch(
                    InRideAlert(
                        id = "zone-change",
                        icon = R.drawable.ic_firefly,
                        title = "$oldZone → $newZone ($reason)",
                        detail = "F: ${frontMode.displayName}\nR: ${rearMode.displayName}",
                        autoDismissMs = 10000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
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
                engine.onRideStart()
                if (frontLightId == null && rearLightId == null) {
                    discoverKarooLights()
                }
            }
            is RideState.Paused -> engine.onRidePause()
            is RideState.Idle -> engine.onRideStop()
        }
    }

    private fun loadSettings() {
        extensionScope.launch {
            val settings = repository.settingsFlow.first()
            engine.settings = settings
            timeController.dawnOffsetMinutes = settings.dawnOffsetMinutes
            timeController.duskOffsetMinutes = settings.duskOffsetMinutes
            engine.updateAmbientSensor()
        }
    }

    /**
     * Query Karoo's saved devices for ANT+ Bike Lights.
     */
    internal fun discoverKarooLights() {
        savedDevicesConsumerId?.let { karooSystem.removeConsumer(it) }
        extensionScope.launch {
            Timber.d("$TAG: Querying Karoo for saved bike light devices")
            savedDevicesConsumerId = karooSystem.addConsumer<SavedDevices> { savedDevices ->
                val lights = savedDevices.devices.filter { device ->
                    device.supportedDataTypes.contains(BIKE_LIGHT_DATA_TYPE) && device.enabled
                }

                Timber.d("$TAG: Found ${lights.size} saved bike light(s)")

                for (device in lights) {
                    val parts = device.id.split("-")
                    if (parts.size >= 3) {
                        val deviceType = parts[1].toIntOrNull()
                        if (deviceType == DEVICE_TYPE_BIKE_LIGHT) {
                            if (frontLightId == null) {
                                frontLightId = device.id
                                Timber.d("$TAG: Front light: ${device.name} (${device.id})")
                            } else if (rearLightId == null) {
                                rearLightId = device.id
                                Timber.d("$TAG: Rear light: ${device.name} (${device.id})")
                            }
                        }
                    }
                }
            }
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
                        detail = "",
                        autoDismissMs = 2000,
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
                        detail = "",
                        autoDismissMs = 2000,
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

    fun dispatchTestZoneAlert() {
        extensionScope.launch {
            kotlinx.coroutines.delay(5000)
            playNotificationSound()
            karooSystem.dispatch(
                InRideAlert(
                    id = "zone-change",
                    icon = R.drawable.ic_firefly,
                    title = "DAY → DUSK (Light sensor)",
                    detail = "F: Steady Low\nR: Steady High",
                    autoDismissMs = 10000,
                    backgroundColor = android.R.color.black,
                    textColor = android.R.color.white,
                ),
            )
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: Extension onDestroy")
        instance = null
        engine.destroy()
        lightControl.unbind()
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
