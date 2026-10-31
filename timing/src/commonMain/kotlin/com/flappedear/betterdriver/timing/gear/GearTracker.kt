package com.flappedear.betterdriver.timing.gear

import kotlin.math.abs
import kotlin.math.ln

/**
 * The gear moment by moment, from engine speed and road speed read in time order (Arek, 2026-10-31:
 * the gear changes where the RPM trace steps, not after a long dash). [Gearbox.gearAt] alone reads a
 * gear only while engine speed over road speed is within 4 % of one, but the FR-S's torque converter
 * slips 4 to 8 % off it for seconds at a time: below the gear's ratio when he lifts and brakes, above
 * it when he gets back on the throttle. On his nine recorded sessions that left a dash for 22 % of the
 * time the car was moving, in runs of up to 10 s, with the gear plain from the trace all along.
 *
 * So the gear is held until a shift: a shift is the engine speed stepping away from the held gear's
 * ratio to another gear's, an upshift a drop and a downshift a blip, while road speed changes smoothly.
 * The gear changes when the ratio has passed the middle between the two gears for [ConfirmMicros]
 * (the gears are 22 % or more apart, the converter's slip stays under 10 %). A gear is first taken only
 * from a reading within [GearToleranceLog] of it; standing, idling or a gap in the readings clears it.
 *
 * The gears are the fitted spec's, all of them, so a shift into a gear the fit did not see (5th on a
 * long straight) still moves the gear instead of being read as slip in the gear beside it.
 */
class GearTracker(box: Gearbox) {
    /** Engine speed per road speed in each gear, lowest first: (gear number, log of rpm per m/s). */
    private var gears: List<Pair<Int, Double>> = logGears(box)

    private var current: Int? = null
    private var pending: Int? = null
    private var pendingSince = 0L
    private var pendingReadings = 0
    private var lastAt = Long.MIN_VALUE

    /** The gear after the last reading; null before one is read, standing, or after a gap. */
    val gear: Int? get() = current

    /** Takes the reading at [tMicros] (in time order) and returns the gear then. */
    @Suppress("ReturnCount")
    fun next(tMicros: Long, rpm: Double, speedMps: Double): Int? {
        if (lastAt != Long.MIN_VALUE && tMicros - lastAt > MaxGapMicros) clear()
        lastAt = tMicros
        if (speedMps < MinGearSpeedMps || rpm < MinGearRpm || gears.isEmpty()) {
            clear()
            return null
        }
        val l = ln(rpm / speedMps)
        val (nearest, logOff) = gears.map { it.first to abs(l - it.second) }.minBy { it.second }
        val held = current
        when {
            held == null -> if (logOff <= GearToleranceLog) current = nearest
            nearest == held -> pending = null
            else -> {
                if (pending != nearest) {
                    pending = nearest
                    pendingSince = tMicros
                    pendingReadings = 0
                }
                pendingReadings++
                if (tMicros - pendingSince >= ConfirmMicros && pendingReadings >= ConfirmReadings) {
                    current = nearest
                    pending = null
                }
            }
        }
        return current
    }

    /** Reads on with [box], a gearbox fitted again from more readings, keeping the gear held. */
    fun refit(box: Gearbox) {
        gears = logGears(box)
        if (gears.none { it.first == current }) clear()
    }

    private fun logGears(box: Gearbox) = allGears(box).map { (gear, rpmPerMps) -> gear to ln(rpmPerMps) }

    private fun clear() {
        current = null
        pending = null
    }

    companion object {
        /** The ratio stays past the middle between two gears this long before the gear changes. */
        const val ConfirmMicros = 200_000L

        /** ...over at least this many readings, so one stray reading never shifts. */
        const val ConfirmReadings = 2

        /** No reading for this long and the gear is not known any more. */
        const val MaxGapMicros = 1_000_000L

        /**
         * Every gear of [box]'s spec with its engine speed per road speed: the seen gears as fitted, the
         * others from the spec's ratios at the fitted scale. Only the seen gears for an unknown spec.
         */
        @Suppress("ReturnCount")
        fun allGears(box: Gearbox): List<Pair<Int, Double>> {
            val seen = box.gears.map { it.gear to it.rpmPerMps }
            val spec = KnownGearboxes.firstOrNull { it.name == box.spec } ?: return seen
            val scales = box.gears.mapNotNull { g -> spec.ratios.getOrNull(g.gear - 1)?.let { g.rpmPerMps / it } }
            if (scales.isEmpty()) return seen
            val scale = scales.sorted()[scales.size / 2]
            return spec.ratios.indices.map { i ->
                val gear = i + 1
                gear to (box.gears.firstOrNull { it.gear == gear }?.rpmPerMps ?: (scale * spec.ratios[i]))
            }
        }
    }
}
