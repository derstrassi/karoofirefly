package io.github.derstrassi.karoofirefly.engine

import io.github.derstrassi.karoofirefly.ant.LightMode
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import io.github.derstrassi.karoofirefly.data.LightControlMode
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
import io.github.derstrassi.karoofirefly.data.LightProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Central state machine for light control.
 *
 * Priority (high → low):
 * 1. Manual Override — user pressed BonusAction → holds until zone change or ride state change
 * 2. Auto Mode — DayTimeZone determines baseline profile
 * 3. Ride State — lights off when ride ends
 */
class LightControlEngine(
    private val timeController: TimeBasedController,
    private val ambientLightSensor: AmbientLightSensor? = null,
) {
    companion object {
        const val ZONE_CHECK_INTERVAL_MS = 30_000L
    }

    enum class EngineState {
        IDLE,
        AUTO_CONTROL,
        MANUAL_OVERRIDE,
        PAUSED,
    }

    /** Callback to set light modes. Called with (frontKarooModeName, rearKarooModeName). */
    var onSetModes: ((String, String) -> Unit)? = null

    /** Callback when the light zone changes. Called with (oldZone, newZone, reason, frontMode, rearMode). */
    var onZoneChange: ((DayTimeZone, DayTimeZone, String, LightMode, LightMode) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var zoneCheckJob: Job? = null
    private var sensorObserveJob: Job? = null

    /** The zone at the time the manual override started — override clears on zone change. */
    private var overrideZone: DayTimeZone? = null

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state

    private val _currentZone = MutableStateFlow(DayTimeZone.DAY)
    val currentZone: StateFlow<DayTimeZone> = _currentZone

    private val _currentFrontMode = MutableStateFlow(LightMode.OFF)
    val currentFrontMode: StateFlow<LightMode> = _currentFrontMode

    private val _currentRearMode = MutableStateFlow(LightMode.OFF)
    val currentRearMode: StateFlow<LightMode> = _currentRearMode

    var settings: LightControllerSettings = LightControllerSettings()

    fun onRideStart() {
        Timber.d("LightControlEngine: ride started")
        if (settings.controlMode == LightControlMode.MANUAL_ONLY) return
        if (settings.autoOnWithRide) {
            // Restore override state if resuming from pause
            _state.value = if (overrideZone != null) EngineState.MANUAL_OVERRIDE else EngineState.AUTO_CONTROL
            applyAutoMode()
            startZoneChecking()
        }
    }

    fun onRidePause() {
        Timber.d("LightControlEngine: ride paused")
        _state.value = EngineState.PAUSED
    }

    fun onRideStop() {
        Timber.d("LightControlEngine: ride stopped")
        stopZoneChecking()
        overrideZone = null
        _state.value = EngineState.IDLE
        if (settings.autoOffWithRide) {
            setModes(LightMode.OFF, LightMode.OFF)
        }
    }

    fun onToggleLights() {
        _state.value = EngineState.MANUAL_OVERRIDE
        val zone = determineCurrentZone()
        overrideZone = zone
        if (_currentFrontMode.value != LightMode.OFF || _currentRearMode.value != LightMode.OFF) {
            setModes(LightMode.OFF, LightMode.OFF)
        } else {
            val (front, rear) = getModesForZone(zone, settings.profile)
            setModes(
                LightMode.fromModeNumber(front) ?: LightMode.OFF,
                LightMode.fromModeNumber(rear) ?: LightMode.OFF,
            )
        }
    }

    fun onCycleMode() {
        _state.value = EngineState.MANUAL_OVERRIDE
        overrideZone = determineCurrentZone()
        val modes = LightMode.CYCLING_MODES
        val idx = modes.indexOf(_currentFrontMode.value)
        val nextMode = modes[(idx + 1) % modes.size]
        setModes(nextMode, nextMode)
    }

    private fun applyAutoMode() {
        val zone = determineCurrentZone()

        // During manual override, only clear it when the zone changes
        if (_state.value == EngineState.MANUAL_OVERRIDE) {
            if (zone != overrideZone) {
                Timber.d("Zone changed during override ($overrideZone → $zone), resuming auto control")
                overrideZone = null
                _state.value = EngineState.AUTO_CONTROL
            } else {
                return
            }
        }

        val previousZone = _currentZone.value
        if (zone == previousZone && _currentFrontMode.value != LightMode.OFF) return

        _currentZone.value = zone

        val (frontNum, rearNum) = getModesForZone(zone, settings.profile)
        val front = LightMode.fromModeNumber(frontNum) ?: LightMode.OFF
        val rear = LightMode.fromModeNumber(rearNum) ?: LightMode.OFF
        setModes(front, rear)

        if (zone != previousZone) {
            val reason = when (settings.controlMode) {
                LightControlMode.TIME_BASED -> "Sunrise/Sunset"
                LightControlMode.AMBIENT_LIGHT -> "Light sensor"
                LightControlMode.COMBINED -> {
                    val timeZone = timeController.getCurrentZone()
                    if (zone != timeZone) "Light sensor" else "Sunrise/Sunset"
                }
                LightControlMode.MANUAL_ONLY -> "Manual"
            }
            Timber.d("Zone: $previousZone → $zone ($reason, front=${front.karooName}, rear=${rear.karooName})")
            onZoneChange?.invoke(previousZone, zone, reason, front, rear)
        }
    }

    /**
     * Determines the current zone based on the configured control mode.
     *
     * - TIME_BASED: uses sunrise/sunset only
     * - AMBIENT_LIGHT: uses the light sensor only
     * - COMBINED: time-based as baseline, sensor can darken but not brighten
     *   (e.g. tunnel during DAY → NIGHT, but headlights at NIGHT stay NIGHT)
     */
    private fun determineCurrentZone(): DayTimeZone {
        return when (settings.controlMode) {
            LightControlMode.MANUAL_ONLY -> DayTimeZone.DAY
            LightControlMode.TIME_BASED -> timeController.getCurrentZone()
            LightControlMode.AMBIENT_LIGHT -> {
                ambientLightSensor?.currentLightZone?.value ?: timeController.getCurrentZone()
            }
            LightControlMode.COMBINED -> {
                val timeZone = timeController.getCurrentZone()
                val sensorZone = ambientLightSensor?.currentLightZone?.value ?: timeZone
                // Sensor can only darken (make zone "worse"), never brighten
                if (sensorZone.ordinal > timeZone.ordinal) sensorZone else timeZone
            }
        }
    }

    /**
     * Start or stop the ambient light sensor based on the current control mode.
     * Called when settings change — the sensor runs continuously when needed,
     * independent of ride state (TYPE_LIGHT is extremely low-power).
     */
    fun updateAmbientSensor() {
        ambientLightSensor?.let {
            if (settings.controlMode == LightControlMode.AMBIENT_LIGHT || settings.controlMode == LightControlMode.COMBINED) {
                it.nightThreshold = settings.ambientNightThreshold
                it.start()
                startSensorObserving()
            } else {
                it.stop()
                stopSensorObserving()
            }
        }
    }

    private fun startSensorObserving() {
        if (sensorObserveJob != null) return
        sensorObserveJob = scope.launch {
            ambientLightSensor?.currentLightZone?.collectLatest {
                if (_state.value == EngineState.AUTO_CONTROL || _state.value == EngineState.MANUAL_OVERRIDE) {
                    applyAutoMode()
                }
            }
        }
    }

    private fun stopSensorObserving() {
        sensorObserveJob?.cancel()
        sensorObserveJob = null
    }

    /**
     * Enable or disable debug mode. When enabled, the engine enters AUTO_CONTROL
     * and starts zone checking without requiring an active ride.
     */
    fun setDebugMode(enabled: Boolean) {
        if (enabled) {
            Timber.d("LightControlEngine: debug mode ON")
            _state.value = EngineState.AUTO_CONTROL
            applyAutoMode()
            startZoneChecking()
        } else {
            Timber.d("LightControlEngine: debug mode OFF")
            stopZoneChecking()
            _state.value = EngineState.IDLE
            setModes(LightMode.OFF, LightMode.OFF)
        }
    }

    fun setDebugLightMode(mode: LightMode) {
        Timber.d("LightControlEngine: debug set mode ${mode.displayName}")
        setModes(mode, mode)
    }

    private fun setModes(front: LightMode, rear: LightMode) {
        _currentFrontMode.value = front
        _currentRearMode.value = rear
        onSetModes?.invoke(front.karooName, rear.karooName)
    }

    private fun getModesForZone(zone: DayTimeZone, profile: LightProfile): Pair<Int, Int> {
        return when (zone) {
            DayTimeZone.DAY -> Pair(profile.dayModeFront, profile.dayModeRear)
            DayTimeZone.NIGHT -> Pair(profile.nightModeFront, profile.nightModeRear)
        }
    }

    private fun startZoneChecking() {
        zoneCheckJob?.cancel()
        zoneCheckJob = scope.launch {
            while (true) {
                delay(ZONE_CHECK_INTERVAL_MS)
                if (_state.value == EngineState.AUTO_CONTROL || _state.value == EngineState.MANUAL_OVERRIDE) {
                    applyAutoMode()
                }
            }
        }
    }

    private fun stopZoneChecking() {
        zoneCheckJob?.cancel()
        zoneCheckJob = null
    }

    fun destroy() {
        zoneCheckJob?.cancel()
        sensorObserveJob?.cancel()
        ambientLightSensor?.stop()
        scope.cancel()
    }
}
