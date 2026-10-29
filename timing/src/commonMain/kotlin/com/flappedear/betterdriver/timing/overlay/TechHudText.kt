package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.format.fixed
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * The Tech HUD's texts (FBBD-347), as FlappedEar Overlays writes them, for the Android painter and the
 * iOS one alike.
 */

/** "1:23.456": a lap time to the thousandth, as Overlays' formatLapTime with 3 decimals. */
fun lapTime(micros: Long): String {
    val millis = (micros.coerceAtLeast(0) / MicrosPerMilli).roundToLong()
    val minutes = millis / MillisPerMinute
    val seconds = (millis / MillisPerSecond % SecondsPerMinute).toString().padStart(2, '0')
    val thousandths = (millis % MillisPerSecond).toString().padStart(Thousandths, '0')
    return "$minutes:$seconds.$thousandths"
}

/** "+0.123", "-0.456": a delta in seconds as Overlays' formatLapDelta with 3 decimals. */
fun lapDelta(seconds: Double): String = (if (seconds > 0) "+" else "") + fixed(seconds, Thousandths)

/** "90° L", "45° R", "0°": the wheel angle as Overlays writes it; never wrapped past a turn. */
fun steeringText(degrees: Double?): String {
    if (degrees == null) return HudDash
    val whole = abs(degrees).roundToInt()
    return if (whole == 0) "0°" else "$whole° ${if (degrees > 0) "L" else "R"}"
}

/** What a widget shows for a value it has none of. */
const val HudDash = "—"

/**
 * The OIL, ATF and COOLANT rows' ranges in °C, as the Tech HUD template sets them: the bar from [min]
 * to [max], the green normal band from [normalLow] to [normalHigh], and the value red from [warning].
 */
class HudRowSpec(
    val label: String,
    val min: Double,
    val max: Double,
    val normalLow: Double,
    val normalHigh: Double,
    val warning: Double,
) {
    /** Where [v] is along the bar, 0 to 1. */
    fun fraction(v: Double): Double = ((v - min) / (max - min)).coerceIn(0.0, 1.0)

    @Suppress("MagicNumber") // The template's ranges.
    companion object {
        val Oil = HudRowSpec("OIL", 60.0, 150.0, 90.0, 125.0, 128.0)
        val Atf = HudRowSpec("ATF", 40.0, 140.0, 70.0, 110.0, 118.0)
        val Coolant = HudRowSpec("COOLANT", 60.0, 120.0, 85.0, 104.0, 105.0)

        /** The range of row [w]; null for a widget that is no row. */
        fun of(w: HudWidget): HudRowSpec? = when (w) {
            HudWidget.Oil -> Oil
            HudWidget.Gearbox -> Atf
            HudWidget.Coolant -> Coolant
            else -> null
        }
    }
}

private const val MicrosPerMilli = 1000.0
private const val MillisPerSecond = 1000L
private const val MillisPerMinute = 60_000L
private const val SecondsPerMinute = 60L
private const val Thousandths = 3
