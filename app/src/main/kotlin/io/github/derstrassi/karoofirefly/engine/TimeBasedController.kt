package io.github.derstrassi.karoofirefly.engine

import ca.rmen.sunrisesunset.SunriseSunset
import io.github.derstrassi.karoofirefly.data.DayTimeZone
import timber.log.Timber
import java.util.Calendar
import java.util.TimeZone

/**
 * Determines the current DayTimeZone based on GPS location and time.
 * Uses ca.rmen:lib-sunrise-sunset for local sunrise/sunset calculation.
 */
class TimeBasedController {

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastSunrise: Calendar? = null
    private var lastSunset: Calendar? = null

    /** Offset in minutes to extend dusk/dawn zone before/after sun events. */
    var dawnOffsetMinutes: Int = 30
    var duskOffsetMinutes: Int = 30

    /**
     * Update with new GPS coordinates. Recalculates sunrise/sunset.
     */
    fun onLocationUpdate(latitude: Double, longitude: Double) {
        lastLatitude = latitude
        lastLongitude = longitude
        recalculate()
    }

    private fun recalculate() {
        val lat = lastLatitude ?: return
        val lng = lastLongitude ?: return

        val now = Calendar.getInstance()
        val result = SunriseSunset.getSunriseSunset(
            now,
            lat,
            lng,
        )
        lastSunrise = result[0]
        lastSunset = result[1]

        Timber.d(
            "Sunrise/sunset calculated: sunrise=%s, sunset=%s",
            lastSunrise?.time,
            lastSunset?.time,
        )
    }

    fun getCurrentZone(): DayTimeZone {
        val sunrise = lastSunrise ?: return DayTimeZone.DAY
        val sunset = lastSunset ?: return DayTimeZone.DAY
        val now = Calendar.getInstance()

        val dayStart = (sunrise.clone() as Calendar).apply {
            add(Calendar.MINUTE, dawnOffsetMinutes)
        }
        val nightStart = (sunset.clone() as Calendar).apply {
            add(Calendar.MINUTE, duskOffsetMinutes)
        }

        return when {
            now.before(dayStart) -> DayTimeZone.NIGHT
            now.before(nightStart) -> DayTimeZone.DAY
            else -> DayTimeZone.NIGHT
        }
    }

    fun hasSunData(): Boolean = lastSunrise != null && lastSunset != null

    fun getSunriseTime(): Calendar? = lastSunrise
    fun getSunsetTime(): Calendar? = lastSunset
}
