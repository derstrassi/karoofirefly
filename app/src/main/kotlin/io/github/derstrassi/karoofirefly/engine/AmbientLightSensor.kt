package io.github.derstrassi.karoofirefly.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Wrapper around Android's ambient light sensor (TYPE_LIGHT).
 *
 * Provides smoothed lux readings and a categorized light level with hysteresis
 * to avoid rapid zone switching.
 */
class AmbientLightSensor(context: Context) : SensorEventListener {

    companion object {
        private const val SMOOTHING_WINDOW_SIZE = 10 // ~2s at ~5Hz sensor rate
        private const val MIN_DWELL_TIME_MS = 10_000L // minimum time before zone can change
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _currentLux = MutableStateFlow(0f)
    val currentLux: StateFlow<Float> = _currentLux

    private val _currentLightZone = MutableStateFlow(DayTimeZone.DAY)
    val currentLightZone: StateFlow<DayTimeZone> = _currentLightZone

    private val luxBuffer = ArrayDeque<Float>(SMOOTHING_WINDOW_SIZE)
    private var lastZoneChangeTime = 0L

    var nightThreshold: Int = 50

    val isAvailable: Boolean get() = lightSensor != null

    private var running = false

    fun start() {
        if (running) return
        if (lightSensor == null) {
            Timber.w("AmbientLightSensor: No light sensor available on this device")
            return
        }
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        running = true
        Timber.d("AmbientLightSensor: started")
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        luxBuffer.clear()
        running = false
        Timber.d("AmbientLightSensor: stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]

        // Update smoothing buffer
        if (luxBuffer.size >= SMOOTHING_WINDOW_SIZE) {
            luxBuffer.removeFirst()
        }
        luxBuffer.addLast(lux)

        val smoothedLux = luxBuffer.average().toFloat()
        _currentLux.value = smoothedLux

        // Categorize with hysteresis (dwell time)
        val newZone = categorize(smoothedLux)
        val currentZone = _currentLightZone.value
        val now = System.currentTimeMillis()

        if (newZone != currentZone && (now - lastZoneChangeTime) >= MIN_DWELL_TIME_MS) {
            Timber.d("AmbientLightSensor: zone change $currentZone → $newZone (lux=$smoothedLux)")
            _currentLightZone.value = newZone
            lastZoneChangeTime = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // not used
    }

    private fun categorize(lux: Float): DayTimeZone {
        return if (lux < nightThreshold) DayTimeZone.NIGHT else DayTimeZone.DAY
    }
}
