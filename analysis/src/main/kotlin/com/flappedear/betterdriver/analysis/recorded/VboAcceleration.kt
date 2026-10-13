package com.flappedear.betterdriver.analysis.recorded

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.accelerationAliasInG
import com.flappedear.betterdriver.analysis.map.TimedG
import com.flappedear.betterdriver.analysis.map.sessionGpsG
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.vbo.VboDerivedChannel
import kotlin.math.roundToLong

/** The VBO column for the car's longitudinal G: + speeding up, - braking. FlappedEar Overlays reads it by this name. */
const val VboLongitudinalG = "longacc"

/** The VBO column for the car's lateral G: + towards the left. FlappedEar Overlays reads it by this name. */
const val VboLateralG = "latacc"

private const val MicrosPerSecond = 1_000_000.0
private const val GDecimals = 3

/**
 * The car's longitudinal and lateral G as VBO columns [VboLongitudinalG] and [VboLateralG], so
 * FlappedEar Overlays has G for its gauges and G-G diagram (Arek, 2026-10-13). The device's own
 * accelerometer axes (accel_x, accel_y) are the receiver's, not the car's: Overlays does not read
 * them as G. Each axis comes from the accelerometer as the analysis corrects it (turned round and
 * levelled against GPS, see [telemetrySessionOf]); an axis that has no usable accelerometer comes
 * from GPS speed and heading instead. The signs are those Overlays expects (RaceChrono's).
 * Empty without positions to work from.
 */
fun vboAccelerations(data: SessionData): List<VboDerivedChannel> {
    val origin = data.sampleSets.minOfOrNull { it.tMicros } ?: return emptyList()
    val session = telemetrySessionOf(data, null)
    val gps by lazy { sessionGpsG(session).orEmpty() }
    @Suppress("ReturnCount")
    fun axis(name: String, alias: String, fromGps: (TimedG) -> Double): VboDerivedChannel? {
        val measured = accelerationAliasInG(session, alias)?.takeIf { it.supported }?.channel
        val channel = measured ?: gps.takeIf { it.isNotEmpty() }?.let { g ->
            TelemetryChannel(name, DoubleArray(g.size) { g[it].time }, DoubleArray(g.size) { fromGps(g[it]) }, "g")
        } ?: return null
        // A gap stays a gap in the analysis; a VBO cell needs a number, so the value before it is held.
        val kept = (0 until channel.sampleCount).filter { channel.values[it].isFinite() }
        if (kept.isEmpty()) return null
        val times = LongArray(kept.size) { origin + (channel.timestamps[kept[it]] * MicrosPerSecond).roundToLong() }
        return VboDerivedChannel(name, "g", GDecimals, times, DoubleArray(kept.size) { channel.values[kept[it]] })
    }
    return listOfNotNull(
        axis(VboLongitudinalG, Aliases.LONGITUDINAL_ACCELERATION) { it.longitudinalG },
        axis(VboLateralG, Aliases.LATERAL_ACCELERATION) { it.lateralG },
    )
}
