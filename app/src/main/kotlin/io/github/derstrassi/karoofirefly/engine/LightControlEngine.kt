package io.github.derstrassi.karoofirefly.engine

import io.github.derstrassi.karoofirefly.data.DayTimeZone
import io.github.derstrassi.karoofirefly.data.LightControlMode
import io.github.derstrassi.karoofirefly.data.LightControllerSettings
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

    var onApplyZone: ((DayTimeZone?) -> Unit)? = null

    var onZoneChange: ((DayTimeZone, DayTimeZone, String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var zoneCheckJob: Job? = null
    private var sensorObserveJob: Job? = null

    private var overrideZone: DayTimeZone? = null

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state

    private val _currentZone = MutableStateFlow(DayTimeZone.DAY)
    val currentZone: StateFlow<DayTimeZone> = _currentZone

    private val _activeZone = MutableStateFlow<DayTimeZone?>(null)
    val activeZone: StateFlow<DayTimeZone?> = _activeZone

    var settings: LightControllerSettings = LightControllerSettings()

    fun onRideStart() {
        Timber.d("LightControlEngine: ride started")
        if (settings.controlMode == LightControlMode.MANUAL_ONLY) return
        if (settings.autoOnWithRide) {
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
            applyZone(null)
        }
    }

    fun onToggleLights() {
        _state.value = EngineState.MANUAL_OVERRIDE
        overrideZone = determineCurrentZone()
        if (_activeZone.value != null) {
            applyZone(null)
        } else {
            val zone = resolveOnZone()
            applyZone(zone)
        }
    }

    private fun resolveOnZone(): DayTimeZone {
        val zone = determineCurrentZone()
        if (hasModesForZone(zone)) return zone
        val otherZone = if (zone == DayTimeZone.DAY) DayTimeZone.NIGHT else DayTimeZone.DAY
        if (hasModesForZone(otherZone)) return otherZone
        return DayTimeZone.DAY
    }

    private fun hasModesForZone(zone: DayTimeZone): Boolean {
        return settings.lightAssignments.any { assignment ->
            val mode = when (zone) {
                DayTimeZone.DAY -> assignment.dayMode
                DayTimeZone.NIGHT -> assignment.nightMode
            }
            mode != "OFF"
        }
    }

    fun onCycleMode() {
        _state.value = EngineState.MANUAL_OVERRIDE
        overrideZone = determineCurrentZone()

        val dayHasModes = hasModesForZone(DayTimeZone.DAY)
        val nightHasModes = hasModesForZone(DayTimeZone.NIGHT)

        when (_activeZone.value) {
            null -> {
                if (dayHasModes) applyZone(DayTimeZone.DAY)
                else if (nightHasModes) applyZone(DayTimeZone.NIGHT)
                else applyZone(DayTimeZone.DAY)
            }
            DayTimeZone.DAY -> {
                if (nightHasModes) applyZone(DayTimeZone.NIGHT)
                else applyZone(null)
            }
            DayTimeZone.NIGHT -> {
                applyZone(null)
            }
        }
    }

    private fun applyAutoMode() {
        val zone = determineCurrentZone()

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
        if (zone == previousZone && _activeZone.value != null) return

        _currentZone.value = zone
        applyZone(zone)

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
            Timber.d("Zone: $previousZone → $zone ($reason)")
            onZoneChange?.invoke(previousZone, zone, reason)
        }
    }

    private fun applyZone(zone: DayTimeZone?) {
        _activeZone.value = zone
        onApplyZone?.invoke(zone)
    }

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
                if (sensorZone.ordinal > timeZone.ordinal) sensorZone else timeZone
            }
        }
    }

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
            applyZone(null)
        }
    }

    fun setDebugZone(zone: DayTimeZone) {
        Timber.d("LightControlEngine: debug set zone ${zone.name}")
        applyZone(zone)
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
