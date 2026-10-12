package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationAliasInG

/**
 * The whole of [session] as one G-G trace (the G-forces card): every [GgStepSeconds] while the car
 * moves at [MinimumGgSpeedMetersPerSecond] or more, from the accelerometer when the session has
 * both its axes (as [telemetrySessionOf][com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf]
 * corrected them), else from GPS. Needs no laps, so a road drive has one too. Null without data.
 */
@Suppress("ReturnCount")
fun sessionGg(session: TelemetrySession): GgTrace? {
    val gps = sessionGpsG(session) ?: return null
    val longitudinal = accelerationAliasInG(session, Aliases.LONGITUDINAL_ACCELERATION)?.takeIf { it.supported }
    val lateral = accelerationAliasInG(session, Aliases.LATERAL_ACCELERATION)?.takeIf { it.supported }
    if (longitudinal != null && lateral != null) {
        val half = AccelerometerSmoothingSeconds
        // At the moments GPS says the car moves, so standing still does not pile up at the centre.
        val points = gps.mapNotNull { g ->
            val x = smoothedAt(longitudinal.channel, g.time, half) ?: return@mapNotNull null
            val y = smoothedAt(lateral.channel, g.time, half) ?: return@mapNotNull null
            GgPoint(y, x, null)
        }
        if (points.size >= gps.size * MinimumAccelerometerCoverage) return GgTrace(GgSource.Accelerometer, points)
    }
    if (gps.isEmpty()) return null
    return GgTrace(GgSource.Gps, gps.map { GgPoint(it.lateralG, it.longitudinalG, null) })
}

/**
 * The acceleration GPS shows over the whole of [session], every [stepSeconds] (see [gpsG]), or null
 * without positions. Its time span is that of the latitude channel.
 */
@Suppress("ReturnCount")
fun sessionGpsG(session: TelemetrySession, stepSeconds: Double = GgStepSeconds): List<TimedG>? {
    val lat = session.channel(Aliases.LATITUDE) ?: return null
    if (lat.sampleCount < 2) return null
    val start = lat.timestamps.first()
    val origin = (0 until lat.sampleCount).firstNotNullOfOrNull { firstPosition(session, lat.timestamps[it]) }
        ?: return null
    val steps = ((lat.timestamps.last() - start) / stepSeconds).toInt()
    return gpsG((0..steps).map { kinematicAt(session, start + it * stepSeconds, origin) }, stepSeconds)
}
