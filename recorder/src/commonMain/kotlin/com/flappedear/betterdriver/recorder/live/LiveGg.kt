package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.math.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
 *
 * A receiver gives two [axes], X and Y. The phone's own accelerometer gives three: it can sit flat,
 * upright or on its side in its mount, so any of its axes can be the car's forward or left, and each
 * carries some gravity, which the match takes off as it takes off a receiver's tilt. Once its axes
 * are levelled ([Level]: across, along, up) it is [turned]: the phone can also sit turned in its mount
 * (on Arek's Jastrząb export its forward G reads on both flat axes), so the match looks for the
 * direction in the flat plane that is forward, every [TurnStepDeg].
 */
internal class LiveGg(
    private val axes: Int = ReceiverAxes,
    private val turned: Boolean = false,
    /** A match started again takes this one's fixes, so it does not wait for the next ones for the GPS's G. */
    fixesFrom: LiveGg? = null,
) {
    private val fixTimes = ArrayDeque<Long>()
    private val fixSpeeds = ArrayDeque<Double>()
    private val fixHeadings = ArrayDeque<Double>()

    /** Each axis's averaged reading at each reading of the [HistoryMicros], for the lagged match. */
    private val accelTimes = ArrayDeque<Long>()
    private val accels = ArrayDeque<DoubleArray>()

    /** Per lag, per axis: the axis against GPS forward and against GPS left. */
    private val fits = Array(FitLagsMicros.size) { Array(axes) { Array(Directions) { Agreement() } } }

    /** Per lag, when [turned]: the two flat axes together against GPS forward and left. */
    private val planes = Array(FitLagsMicros.size) { Plane() }
    private var mapping: AxisMapping? = null

    /** The newest fix already paired with a reading: each fix counts once in the match. */
    private var fittedFix = Long.MIN_VALUE

    /** The driving the match has seen: fix to fix, a gap counted up to [MaxFitStepMicros]. */
    private var fittedMicros = 0L

    private var now: GPoint? = null
    private var nowMicros = Long.MIN_VALUE
    private val trail = ArrayDeque<Pair<Long, GPoint>>()

    init {
        fixesFrom?.let {
            fixTimes.addAll(it.fixTimes)
            fixSpeeds.addAll(it.fixSpeeds)
            fixHeadings.addAll(it.fixHeadings)
        }
    }

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
     * Each axis now ([a], one value per axis), averaged over the [GSmoothingMicros] as the G tile. A
     * RaceBox sends its fix and acceleration in one set, RaceChrono DIY input in two (NmeaDecoder), and
     * the phone's GPS stamps its fix up to a couple of seconds back, after readings of later moments.
     */
    fun addAccel(t: Long, a: DoubleArray) {
        if (a.size != axes || a.any { !it.isFinite() }) return
        accelTimes.addLast(t)
        accels.addLast(a.copyOf())
        while (accelTimes.first() < t - HistoryMicros) {
            accelTimes.removeFirst()
            accels.removeFirst()
        }
        fit()
        update(t, mapping?.let { GPoint(it.forward(a), it.left(a)) } ?: gpsG(t))
    }

    /** A fix without an accelerometer reading in its set: the radar shows the GPS's G until the axes agree. */
    fun fixOnly(t: Long) {
        fit()
        if (mapping == null) update(t, gpsG(t))
    }

    /**
     * Pairs the newest fix, once, with the readings at and before its moment, when they are here: with
     * the fix's own set, or later for a fix that came before its readings.
     */
    private fun fit() {
        val fix = fixTimes.lastOrNull() ?: return
        val due = fix > fittedFix && fixSpeeds.last() * KmhPerMps >= FitMinKmh && accelAt(fix) != null
        val gps = (if (due) gpsG(fix) else null) ?: return
        if (fittedFix != Long.MIN_VALUE) fittedMicros += minOf(fix - fittedFix, MaxFitStepMicros)
        fittedFix = fix
        FitLagsMicros.forEachIndexed { i, lag ->
            val a = accelAt(fix - lag) ?: return@forEachIndexed
            if (turned) planes[i].add(a[0], a[1], gps)
            for (axis in 0 until axes) {
                fits[i][axis][Forward].add(a[axis], gps.forward)
                fits[i][axis][Left].add(a[axis], gps.left)
            }
        }
        mapping = when {
            fittedMicros < MinimumFitMicros -> null
            turned -> bestTurn(planes, axes)
            else -> bestMapping()
        }
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

    /** The averaged axes at [t], from the reading nearest to it; null without one close enough. */
    private fun accelAt(t: Long): DoubleArray? {
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
        return accels[best]
    }

    /**
     * The lag and assignment (which axis is forward, which another is left) where both agree best with
     * GPS; null until both agree at [MinimumLiveCorrelation] over at least [MinimumLivePairs] pairs.
     */
    private fun bestMapping(): AxisMapping? {
        val pairs = (0 until axes).flatMap { f -> (0 until axes).filter { it != f }.map { f to it } }
        var best: AxisMapping? = null
        var bestScore = 0.0
        for (lag in fits) {
            if (lag[0][Forward].n < MinimumLivePairs) continue
            for ((fa, la) in pairs) {
                val f = lag[fa][Forward]
                val l = lag[la][Left]
                val cf = abs(f.correlation())
                val cl = abs(l.correlation())
                if (cf >= MinimumLiveCorrelation && cl >= MinimumLiveCorrelation && cf + cl > bestScore) {
                    bestScore = cf + cl
                    best = AxisMapping(f.line().along(fa, axes), l.line().along(la, axes))
                }
            }
        }
        return best
    }
}

private const val Forward = 0
private const val Left = 1
private const val Directions = 2

/** A receiver's accelerometer axes, X and Y. */
internal const val ReceiverAxes = 2

/** The phone's accelerometer axes, X, Y and Z. */
internal const val PhoneAxes = 3

/**
 * As LiveGg's match for a levelled phone: the lag and the direction in the flat plane (with left a
 * quarter turn from it, either way) where both agree best with GPS.
 */
private fun bestTurn(planes: Array<Plane>, axes: Int): AxisMapping? {
    var best: AxisMapping? = null
    var bestScore = 0.0
    for (plane in planes) {
        if (plane.n < MinimumLivePairs) continue
        for ((forward, left) in TurnDirections) {
            val cf = plane.correlation(forward, Forward)
            val cl = plane.correlation(left, Left)
            if (cf >= MinimumLiveCorrelation && cl >= MinimumLiveCorrelation && cf + cl > bestScore) {
                bestScore = cf + cl
                best = AxisMapping(plane.line(forward, Forward, axes), plane.line(left, Left, axes))
            }
        }
    }
    return best
}

/** Forward every [TurnStepDeg] round the flat plane, each with left a quarter turn from it either way. */
private val TurnDirections: List<Pair<DoubleArray, DoubleArray>> =
    (0 until FullTurn.toInt() / TurnStepDeg).flatMap { step ->
        val angle = toRadians(step * TurnStepDeg.toDouble())
        val c = cos(angle)
        val s = sin(angle)
        listOf(doubleArrayOf(c, s) to doubleArrayOf(-s, c), doubleArrayOf(c, s) to doubleArrayOf(s, -c))
    }

/** How a reading `a` of one axis relates to GPS: `a = sign * gps + bias`, as in fitAxis. */
private class AxisLine(val sign: Double, val bias: Double) {
    /** As a [Direction] that reads only [axis] of [axes]. */
    fun along(axis: Int, axes: Int) = Direction(DoubleArray(axes) { if (it == axis) sign else 0.0 }, sign * bias)
}

/** One of the car's directions from the axes: the readings weighted by [weights], less [offset]. */
private class Direction(private val weights: DoubleArray, private val offset: Double) {
    fun read(a: DoubleArray): Double {
        var sum = -offset
        for (i in weights.indices) sum += weights[i] * a[i]
        return sum
    }
}

/** Where the car's forward and left are among the axes. */
private class AxisMapping(private val forward: Direction, private val left: Direction) {
    fun forward(a: DoubleArray) = forward.read(a)
    fun left(a: DoubleArray) = left.read(a)
}

/**
 * Running sums for how the two flat axes `p` and `q` together agree with GPS forward and left over
 * the session, so the agreement of any direction in their plane can be worked out from them.
 */
private class Plane {
    var n = 0
        private set
    private val s = DoubleArray(2)
    private val ss = DoubleArray(3)
    private val sg = DoubleArray(2)
    private val sgg = DoubleArray(2)

    /** Per GPS direction: p times it, q times it. */
    private val sag = Array(2) { DoubleArray(2) }

    fun add(p: Double, q: Double, gps: GPoint) {
        n++
        s[0] += p
        s[1] += q
        ss[0] += p * p
        ss[1] += q * q
        ss[2] += p * q
        for ((i, g) in listOf(gps.forward, gps.left).withIndex()) {
            sg[i] += g
            sgg[i] += g * g
            sag[i][0] += p * g
            sag[i][1] += q * g
        }
    }

    /** How the reading along [d] (a unit direction in the plane) agrees with GPS direction [gps]. */
    fun correlation(d: DoubleArray, gps: Int): Double {
        val vpp = ss[0] - s[0] * s[0] / n
        val vqq = ss[1] - s[1] * s[1] / n
        val vpq = ss[2] - s[0] * s[1] / n
        val va = d[0] * d[0] * vpp + d[1] * d[1] * vqq + 2 * d[0] * d[1] * vpq
        val vg = sgg[gps] - sg[gps] * sg[gps] / n
        val cov = d[0] * (sag[gps][0] - s[0] * sg[gps] / n) + d[1] * (sag[gps][1] - s[1] * sg[gps] / n)
        return if (va <= 0.0 || vg <= 0.0) 0.0 else cov / sqrt(va * vg)
    }

    /** The reading along [d] as the car's direction [gps], its bias the difference of the means. */
    fun line(d: DoubleArray, gps: Int, axes: Int): Direction {
        val mean = (d[0] * s[0] + d[1] * s[1]) / n
        return Direction(DoubleArray(axes) { if (it < 2) d[it] else 0.0 }, mean - sg[gps] / n)
    }
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

/** The step of the directions the match tries in a levelled phone's flat plane. */
private const val TurnStepDeg = 5

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

/** About 10 s of driving before the axes are trusted: 250 pairs at the RaceBox's 25 Hz. */
private const val MinimumFitMicros = 10_000_000L

/** And at least this many pairs: a minute of driving on the phone's 1 Hz GPS. */
private const val MinimumLivePairs = 60

/** A longer gap between paired fixes (stopped, slow, or lost) counts as this much driving. */
private const val MaxFitStepMicros = 2_000_000L

/** GPS G needs fixes at least this far apart: a 1 Hz receiver gives none until its second fix. */
private const val MinGpsSpanMicros = 200_000L

/**
 * A fix older than this gives no GPS G: lost, or a 1 Hz receiver's fix missed. The phone's 1 Hz GPS
 * stamps a fix when it was taken, often well before it arrives, so its newest fix can be 2 s old.
 */
private const val StaleFixMicros = 2_500_000L

/** A lagged reading pairs only with a reading this close to its time. */
private const val MaxPairGapMicros = 60_000L

/** Kept for the lagged match: the longest lag, and the phone GPS's fix up to 2 s old (phoneFixMicros). */
private const val HistoryMicros = 3_000_000L

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
