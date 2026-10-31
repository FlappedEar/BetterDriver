package com.flappedear.betterdriver.timing.gear

import com.flappedear.betterdriver.core.JvmSynchronized
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.timing.overlay.CarValue
import kotlin.math.abs

/**
 * The gear the car is in now, for the track screen's Gear tile (FBBD-397, UI-309 A). Like the gear in
 * each corner (FBBD-326) it comes from engine speed over road speed, with the gearbox this session's
 * own readings fit ([fitGearbox]): the car needs nothing set up, and a car whose engine speed follows
 * no fixed ratios (a hybrid, a CVT) gets no gear. The gearbox is fitted again every [RefitEvery]
 * readings from the last [MaxReadings], so the tile has a gear about a minute after the car first
 * drives with engine speed.
 *
 * A [RecorderListener] on the session's samples, so it runs whether the screen is on or not;
 * synchronised on the JVM, and on iOS the caller serialises the calls, as for LiveTracker.
 */
class LiveGear(private val nowMicros: () -> Long) : RecorderListener {
    private var rpmId: Int? = null
    private var speedMps: Double? = null
    private var speedAt = Long.MIN_VALUE
    private var rpm: Double? = null
    private var rpmAt = Long.MIN_VALUE
    private val readings = ArrayDeque<DriveSample>()
    private var lastKept = Long.MIN_VALUE
    private var sinceFit = 0
    private var failedFits = 0
    private var tracker: GearTracker? = null

    /** The gearbox fitted from this session so far; null before the first fit or when none fits. */
    var gearbox: Gearbox? = null
        @JvmSynchronized get
        private set

    @JvmSynchronized
    override fun onChannel(def: ChannelDef) {
        if (rpmId == null && CarValue.Rpm.matches(def.name)) rpmId = def.id
    }

    @JvmSynchronized
    override fun onSamples(set: SampleSet) {
        val rpmChannel = rpmId
        for (v in set.values) {
            if (!v.value.isFinite()) continue
            when (v.channelId) {
                GnssChannels.SPEED_KMH.id -> {
                    speedMps = v.value / KmhPerMps
                    speedAt = set.tMicros
                }
                rpmChannel -> {
                    rpm = v.value
                    rpmAt = set.tMicros
                    keep(set.tMicros)
                    track(set.tMicros)
                }
            }
        }
    }

    /** Keeps an engine speed reading with the road speed of the same moment, at most one per [KeepStepMicros]. */
    @Suppress("ReturnCount")
    private fun keep(t: Long) {
        val s = speedMps ?: return
        val r = rpm ?: return
        val tooSoon = lastKept != Long.MIN_VALUE && t - lastKept < KeepStepMicros
        if (abs(t - speedAt) > PairWindowMicros || tooSoon) return
        lastKept = t
        readings.addLast(DriveSample(t / MicrosPerSecond, s, r, null))
        if (readings.size > MaxReadings) readings.removeFirst()
        if (++sinceFit >= RefitEvery) {
            sinceFit = 0
            refit()
        }
    }

    /**
     * Fits the gearbox again from the readings kept. One ratio alone (a hybrid cruising steadily) is
     * no gearbox; a fit that later readings disprove is dropped, so an engine without fixed gears
     * loses the tile again.
     */
    private fun refit() {
        val fit = fitGearbox(listOf(readings.toList()))?.takeIf { it.gears.size >= MinGears }
        if (fit != null) {
            gearbox = fit
            tracker.let { if (it == null) tracker = GearTracker(fit) else it.refit(fit) }
            failedFits = 0
        } else if (drivenReadings() >= MinFitReadings) {
            gearbox = null
            tracker = null
            failedFits++
        }
    }

    /** Every engine speed reading with a road speed of the same moment moves the gear on ([GearTracker]). */
    private fun track(t: Long) {
        val s = speedMps ?: return
        val r = rpm ?: return
        if (abs(t - speedAt) <= PairWindowMicros) tracker?.next(t, r, s)
    }

    /** Readings fast enough for a gear: only fits with enough of them count as failed. */
    private fun drivenReadings(): Int =
        readings.count { it.speedMps >= MinGearSpeedMps && (it.rpm ?: 0.0) >= MinGearRpm }

    /**
     * The gear now, 1 for first: held through the converter's slip and changed where engine speed
     * steps to another gear ([GearTracker]). Null standing, when engine speed or road speed is older
     * than [FreshMicros], before a gearbox fits, or before a reading has sat on a gear.
     */
    @JvmSynchronized
    @Suppress("ReturnCount")
    fun gear(): Int? {
        val now = nowMicros()
        if (now - speedAt > FreshMicros || now - rpmAt > FreshMicros) return null
        return tracker?.gear
    }

    /**
     * Whether the Gear tile has data in this session: the car sends engine speed, and either a gearbox
     * fitted or the session has not yet shown that none will ([GiveUpFits] fits without one).
     */
    @JvmSynchronized
    fun shows(): Boolean = rpmAt != Long.MIN_VALUE && (gearbox != null || failedFits < GiveUpFits)

    /**
     * [snapshot] with the gear as the channel [GearChannel] for the tiles: the gear while it reads, NaN
     * (a dash) while it does not, and left out where [shows] is false, so the tile is left out (UI-126 B).
     */
    fun withGear(snapshot: LiveSnapshot): LiveSnapshot {
        if (!shows()) return snapshot
        val g = gear()?.toDouble() ?: Double.NaN
        return snapshot.copy(channels = snapshot.channels + (GearChannel to g))
    }

    companion object {
        /** The Gear tile's id and its channel name in [LiveSnapshot.channels] (setup TileIds.GEAR). */
        const val GearChannel = "gear"

        /** Readings kept to fit from: about ten minutes at [KeepStepMicros]. */
        const val MaxReadings = 6_000

        /** Fit again every this many readings: ten seconds of engine speed. */
        const val RefitEvery = 100

        /** Fits without a gearbox, each with enough readings, before the tile is left out. */
        const val GiveUpFits = 6

        /** A gearbox shows at least two gears in the readings kept. */
        private const val MinGears = 2

        /** As [fitGearbox] needs at least. */
        private const val MinFitReadings = 200
        private const val KeepStepMicros = 90_000L
        private const val PairWindowMicros = 150_000L
        private const val FreshMicros = 1_000_000L
        private const val MicrosPerSecond = 1_000_000.0
        private const val KmhPerMps = 3.6
    }
}
