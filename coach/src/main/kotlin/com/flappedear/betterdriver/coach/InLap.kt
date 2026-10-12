package com.flappedear.betterdriver.coach

/** A lap that ends slower than this share of the reference lap's speed at the line ends in the pit lane. */
private const val PitLaneShare = 0.5

/** Slowing at least this hard, but less than braking ([CornerBrakingMps2]), is lifting off. */
private const val LiftMps2 = 1.0

/** Lifting off for this long on a straight is cooling down; easing off before braking is shorter. */
private const val LiftMicros = 3_000_000L

/** While lifting off the speed never rises by more than this from one fix to the next (receiver noise). */
private const val NoiseKmh = 0.5

/** The reference lap slowed over a stretch when it lost more than this. */
private const val ReferenceSlowerKmh = 2.0

private const val KmhPerMps = 3.6
private const val MicrosPerSecond = 1_000_000.0

/**
 * Whether the driver is on the in-lap (FBBD-202), where the coach says nothing:
 *
 * - **Cooling down.** On a straight the car slowed all the time for 3 s, gently: lifting off, not
 *   braking or easing off before braking, where the reference lap did not slow (at Silesia Ring on
 *   27 Sep 2026: about 1.5 m/s² from 130 km/h, just past the line on the last lap). It lasts to the
 *   end of the lap.
 * - **Pit lane.** A lap ended at less than half the reference lap's speed at the line: that lap was
 *   the in-lap. Its time is not said, and nothing is said until the car is back up to that speed.
 *
 * A lift of 3 s for traffic also quiets the rest of that lap; the next lap is coached again.
 */
internal class InLap {
    private class Sample(val tMicros: Long, val kmh: Double, val along: Double)

    private val straight = ArrayDeque<Sample>()
    private var cooling = false
    private var pitLaneBelowKmh: Double? = null

    /** Nothing is to be said. */
    val quiet: Boolean get() = cooling || pitLaneBelowKmh != null

    /**
     * A lap ended with the car at [kmh]; [path] is the reference lap, if known. Returns whether the
     * lap ended in the pit lane.
     */
    fun onLapDone(kmh: Double, path: LapTrace?): Boolean {
        cooling = false
        straight.clear()
        pitLaneBelowKmh = path?.let { it.speedsKmh.last() * PitLaneShare }?.takeIf { kmh < it }
        return pitLaneBelowKmh != null
    }

    /** The car at [kmh] at [tMicros]; [along] the reference [path] while on a straight, else null. */
    fun update(tMicros: Long, kmh: Double, along: Double?, path: LapTrace?) {
        pitLaneBelowKmh?.let { if (kmh >= it) pitLaneBelowKmh = null }
        if (along == null || path == null) {
            straight.clear()
            return
        }
        straight.addLast(Sample(tMicros, kmh, along))
        while (straight.size > 1 && tMicros - straight[1].tMicros >= LiftMicros) straight.removeFirst()
        val first = straight.first()
        if (tMicros - first.tMicros < LiftMicros) return
        val slowing = (first.kmh - kmh) / KmhPerMps / ((tMicros - first.tMicros) / MicrosPerSecond)
        val lifted = slowing >= LiftMps2 && slowing < CornerBrakingMps2 &&
            straight.zipWithNext().all { (a, b) -> b.kmh <= a.kmh + NoiseKmh }
        val referenceSlowed = path.speedAt(first.along) - path.speedAt(along) > ReferenceSlowerKmh
        if (lifted && !referenceSlowed) cooling = true
    }
}
