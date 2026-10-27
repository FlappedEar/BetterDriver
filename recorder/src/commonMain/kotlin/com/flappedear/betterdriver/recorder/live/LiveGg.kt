package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.math.toRadians
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The car's acceleration in g for the G radar (FBBD-318): [forward] positive speeding up and
 * negative braking, [left] positive in a left turn.
 */
data class GPoint(val forward: Double, val left: Double)

/**
 * The G radar's dot and trail. The G tile only needs the size of G, so it takes the receiver's X and
 * Y as they come; the radar needs their direction, and a receiver's X can run backwards or be turned
 * across the car (Arek's RaceBox, FBBD-204). So, as the analysis does, each axis is matched to the
 * acceleration worked out from GPS (speed and heading), here as the session goes: until the axes
 * agree with GPS the radar shows the GPS's own G, then the accelerometer's, which is not late.
 *
 * Both are averaged over the [GSmoothingMicros] before now, like the G tile. Some receivers send a fix
 * about 0.8 s after its moment (FBBD-229), so the match is looked for at each of [FitLagsMicros].
 */
internal class LiveGg {
    private val fixTimes = ArrayDeque<Long>()
    private val fixSpeeds = ArrayDeque<Double>()
    private val fixHeadings = ArrayDeque<Double>()

    /** The receiver's averaged X and Y at each reading of the last second, for the lagged match. */
    private val accelTimes = ArrayDeque<Long>()
    private val accelXs = ArrayDeque<Double>()
    private val accelYs = ArrayDeque<Double>()

    /** Per lag: X and Y against GPS forward and left. */
    private val fits = Array(FitLagsMicros.size) { Array(Pairs) { Agreement() } }
    private var mapping: AxisMapping? = null

    /** The newest fix already paired with a reading: each fix counts once in the match. */
    private var fittedFix = Long.MIN_VALUE

    private var now: GPoint? = null
    private var nowMicros = Long.MIN_VALUE
    private val trail = ArrayDeque<Pair<Long, GPoint>>()

    /** A fix: speed in km/h and heading in degrees, clockwise from north. */
    fun addFix(t: Long, speedKmh: Double, headingDeg: Double?) {
        if (!speedKmh.isFinite() || t < (fixTimes.lastOrNull() ?: Long.MIN_VALUE)) return
        fixTimes.addLast(t)
        fixSpeeds.addLast(speedKmh / KmhPerMps)
        fixHeadings.addLast(headingDeg?.takeIf { it.isFinite() } ?: Double.NaN)
        while (fixTimes.size > 2 && fixTimes[1] <= t - GSmoothingMicros) {
            fixTimes.removeFirst()
            fixSpeeds.removeFirst()
            fixHeadings.removeFirst()
        }
    }

    /**
     * The receiver's X and Y now, each averaged over the [GSmoothingMicros] (as the G tile), with
     * the standing tilt taken off. The newest fix, once, if it came with this reading or just before
     * it: a RaceBox sends both in one set, RaceChrono DIY input in two (NmeaDecoder).
     */
    fun addAccel(t: Long, x: Double, y: Double) {
        accelTimes.addLast(t)
        accelXs.addLast(x)
        accelYs.addLast(y)
        while (accelTimes.first() < t - HistoryMicros) {
            accelTimes.removeFirst()
            accelXs.removeFirst()
            accelYs.removeFirst()
        }
        val gps = gpsG(t)
        val fix = fixTimes.lastOrNull() ?: Long.MIN_VALUE
        val fit = fix > fittedFix && t - fix in 0..MaxPairGapMicros
        if (fit && gps != null && fixSpeeds.last() * KmhPerMps >= FitMinKmh) {
            fittedFix = fix
            FitLagsMicros.forEachIndexed { i, lag ->
                accelAt(t - lag)?.let { (ax, ay) -> addPair(fits[i], ax, ay, gps) }
            }
            mapping = bestMapping()
        }
        update(t, mapping?.let { GPoint(it.forward(x, y), it.left(x, y)) } ?: gps)
    }

    /** A fix without an accelerometer reading in its set: the radar shows the GPS's G until the axes agree. */
    fun fixOnly(t: Long) {
        if (mapping == null) update(t, gpsG(t))
    }

    /** The G now, or null before there is one. */
    fun current(): GPoint? = now

    /** The trail behind [current], oldest first: a point every [TrailStepMicros] of the half second before it. */
    fun trail(): List<GPoint> =
        trail.filter { it.first > nowMicros - GSmoothingMicros && it.first < nowMicros }.map { it.second }

    /** A null [g] takes the dot away: the GPS's G of a lost fix is not the G now. */
    private fun update(t: Long, g: GPoint?) {
        if (t < nowMicros) return
        now = g
        nowMicros = t
        if (g == null) {
            trail.clear()
            return
        }
        if (trail.isEmpty() || t - trail.last().first >= TrailStepMicros) trail.addLast(t to g)
        while (trail.first().first <= t - GSmoothingMicros) trail.removeFirst()
    }

    /**
     * Acceleration from the fixes of the last [GSmoothingMicros]: forward from the change of speed,
     * left from speed times the turn rate. Null with less than [MinGpsSpanMicros] between them, or
     * when the newest fix is more than [StaleFixMicros] before [t].
     */
    private fun gpsG(t: Long): GPoint? {
        val span = if (fixTimes.size < 2) 0L else fixTimes.last() - fixTimes.first()
        if (span < MinGpsSpanMicros || t - fixTimes.last() > StaleFixMicros) return null
        val dt = span / MicrosPerSecond
        val forward = (fixSpeeds.last() - fixSpeeds.first()) / dt / StandardGravity
        val h0 = fixHeadings.first()
        val h1 = fixHeadings.last()
        val speed = fixSpeeds.average()
        // Heading wanders standing still; a turn rate needs the car to move.
        val left = if (h0.isNaN() || h1.isNaN() || speed * KmhPerMps < TurnMinKmh) {
            0.0
        } else {
            // Heading grows clockwise, so a left turn makes it smaller.
            -speed * toRadians(wrap(h1 - h0)) / dt / StandardGravity
        }
        return GPoint(forward, left)
    }

    /** The averaged X and Y at [t], from the reading nearest to it; null without one close enough. */
    private fun accelAt(t: Long): Pair<Double, Double>? {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in accelTimes.indices) {
            val gap = abs(accelTimes[i] - t)
            if (gap < bestGap) {
                best = i
                bestGap = gap
            }
        }
        if (best < 0 || bestGap > MaxPairGapMicros) return null
        return accelXs[best] to accelYs[best]
    }

    private fun addPair(lag: Array<Agreement>, x: Double, y: Double, gps: GPoint) {
        lag[XForward].add(x, gps.forward)
        lag[XLeft].add(x, gps.left)
        lag[YForward].add(y, gps.forward)
        lag[YLeft].add(y, gps.left)
    }

    /**
     * The lag and assignment (X forward and Y left, or across) where both axes agree best with GPS;
     * null until both agree at [MinimumLiveCorrelation] over [MinimumLivePairs] pairs.
     */
    private fun bestMapping(): AxisMapping? {
        var best: AxisMapping? = null
        var bestScore = 0.0
        for (lag in fits) {
            if (lag[XForward].n < MinimumLivePairs) continue
            for (across in listOf(false, true)) {
                val f = if (across) lag[YForward] else lag[XForward]
                val l = if (across) lag[XLeft] else lag[YLeft]
                val cf = f.correlation()
                val cl = l.correlation()
                if (abs(cf) < MinimumLiveCorrelation || abs(cl) < MinimumLiveCorrelation) continue
                val score = abs(cf) + abs(cl)
                if (score > bestScore) {
                    bestScore = score
                    best = AxisMapping(across, f.line(), l.line())
                }
            }
        }
        return best
    }

    private companion object {
        const val XForward = 0
        const val XLeft = 1
        const val YForward = 2
        const val YLeft = 3
        const val Pairs = 4
    }
}

/** How a reading `a` of one axis relates to GPS: `a = sign * gps + bias`, as in fitAxis. */
private class AxisLine(val sign: Double, val bias: Double) {
    fun correct(a: Double) = (a - bias) * sign
}

/** Which axis is forward and which left, and how each reads. */
private class AxisMapping(private val across: Boolean, private val forward: AxisLine, private val left: AxisLine) {
    fun forward(x: Double, y: Double) = forward.correct(if (across) y else x)
    fun left(x: Double, y: Double) = left.correct(if (across) x else y)
}

/**
 * Running sums for how one axis agrees with one GPS direction over the session. As in fitAxis, the
 * bias is the difference of the means with the sign applied, not a fitted slope.
 */
private class Agreement {
    var n = 0
        private set
    private var sa = 0.0
    private var sg = 0.0
    private var saa = 0.0
    private var sgg = 0.0
    private var sag = 0.0

    fun add(a: Double, g: Double) {
        n++
        sa += a
        sg += g
        saa += a * a
        sgg += g * g
        sag += a * g
    }

    fun correlation(): Double {
        val cov = sag - sa * sg / n
        val va = saa - sa * sa / n
        val vg = sgg - sg * sg / n
        return if (va <= 0.0 || vg <= 0.0) 0.0 else cov / sqrt(va * vg)
    }

    fun line(): AxisLine {
        val sign = if (correlation() < 0.0) -1.0 else 1.0
        return AxisLine(sign, sa / n - sign * sg / n)
    }
}

private fun wrap(deg: Double): Double = ((deg + HalfTurn) % FullTurn + FullTurn) % FullTurn - HalfTurn

/** The radar's trail keeps a point this often: five in its half second. */
const val TrailStepMicros = 100_000L

/** Lags at which the accelerometer is matched to GPS: on time up to the 0.8 s some receivers' fixes are late. */
private val FitLagsMicros = LongArray(9) { it * 100_000L }

/** The match counts only while moving: standing, GPS speed and heading are noise. */
private const val FitMinKmh = 20.0

/** Below this the heading does not give a turn rate. */
private const val TurnMinKmh = 5.0

/**
 * Both axes must agree with GPS at least this well; below it the radar keeps the GPS's G, which is
 * right but a quarter second later. Stricter than the analysis's 0.5, because live there is no
 * second look: on Arek's Jastrząb exports a flat receiver agrees at 0.94 forward and 0.98 sideways and
 * then reads within 0.1 g of RaceChrono's G, while a phone tilted back agrees at 0.53 and 0.86 and
 * reads 0.36 g off forward.
 */
private const val MinimumLiveCorrelation = 0.7

/** About 10 s of driving at the RaceBox's 25 Hz before the axes are trusted. */
private const val MinimumLivePairs = 250

/** GPS G needs fixes at least this far apart: a 1 Hz receiver gives none until its second fix. */
private const val MinGpsSpanMicros = 200_000L

/** A fix older than this gives no GPS G: lost, or a 1 Hz receiver's fix missed. */
private const val StaleFixMicros = 1_500_000L

/** A lagged reading pairs only with a reading this close to its time. */
private const val MaxPairGapMicros = 60_000L

/** Kept for the lagged match: the longest lag and some. */
private const val HistoryMicros = 1_000_000L

private const val KmhPerMps = 3.6
private const val StandardGravity = 9.80665
private const val MicrosPerSecond = 1_000_000.0
private const val HalfTurn = 180.0
private const val FullTurn = 360.0

/**
 * How far back the live G averages the accelerometer (FBBD-302). RaceChrono's G channels in Arek's
 * Jastrząb exports match GPS acceleration smoothed over 0.5 to 0.7 s; a single 25 Hz reading jumps
 * with every bump, and combining unsmoothed X and Y adds that noise to the G shown.
 */
const val GSmoothingMicros = 500_000L
