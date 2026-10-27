@file:Suppress(
    "ReturnCount",
    "MagicNumber",
    "TooManyFunctions",
) // Ported as written.

package com.flappedear.betterdriver.analysis.data


// Port of FlappedEar Telemetry channel_units.dart and speed_units.dart (Apache 2.0): the unit a
// recording declares for a channel, and how analysis reads quantities in it. A VBO writes most
// units only on a `[header]` line, so a channel's own unit is often empty; analysis asks here.
// Stored samples are never relabelled; a calculation converts the quantity it reads.

/** Standard gravity in m/s²: one g. */
const val StandardGravity = 9.80665

/**
 * The unit [session] declares for channel [name], as written: the channel's own unit, else the
 * header line naming the channel, such as `latacc-calc g`. Empty when it declares none.
 */
fun declaredChannelUnit(session: TelemetrySession, name: String): String {
    val own = session.channels[name]?.unit?.trim().orEmpty()
    if (own.isNotEmpty()) return own
    for ((key, value) in session.metadata) {
        if (!key.startsWith("header.")) continue
        val words = value.trim().split(Regex("\\s+"))
        if (words.size >= 2 && words.first().equals(name, ignoreCase = true)) return words.drop(1).joinToString(" ")
    }
    return ""
}

private fun unitKey(unit: String) = unit.trim().lowercase().replace(" ", "")

/** Gravities in one [unit] of acceleration ("g" and no unit are g); null for a unit not known. */
fun accelerationGPerUnit(unit: String): Double? = when (unitKey(unit)) {
    "", "g" -> 1.0
    "m/s2", "m/s^2", "m/s²" -> 1.0 / StandardGravity
    else -> null
}

/** An acceleration channel as a calculation reads it: in g. */
class AccelerationInG(
    /** The samples in g when the declared unit is known; as recorded otherwise. */
    val channel: TelemetryChannel,
    /** The unit the recording declares, as written; empty when it declares none. */
    val declaredUnit: String,
    /** Whether the declared unit can be read as g. */
    val supported: Boolean,
) {
    val declared: Boolean get() = declaredUnit.isNotEmpty()

    /** The declared spelling of g, "g" for a unit converted to it, empty when none is declared. */
    val unit: String
        get() = when {
            !declared || !supported -> declaredUnit
            accelerationGPerUnit(declaredUnit) == 1.0 -> declaredUnit
            else -> "g"
        }
}

private val accelerationViews = weakMemo<TelemetryChannel, Memo<String, TelemetryChannel>>()

/**
 * Channel [name] of [session] as an acceleration in g: its declared unit recovered from the
 * header, and its samples converted (as float32, as Telemetry stores them) when that unit is
 * m/s². Null when the session has no such channel.
 */
fun accelerationInG(session: TelemetrySession, name: String): AccelerationInG? {
    val found = session.channels[name] ?: return null
    val unit = declaredChannelUnit(session, name)
    val factor = accelerationGPerUnit(unit) ?: return AccelerationInG(found, unit, false)
    if (unit.isEmpty()) return AccelerationInG(found, unit, true)
    val views = accelerationViews.getOrPut(found) { memo() }
    val converted = views.getOrPut(unitKey(unit)) {
        if (factor == 1.0 && found.unit == "g") {
            found
        } else {
            val values = if (factor == 1.0) {
                found.values
            } else {
                DoubleArray(found.values.size) { (found.values[it] * factor).toFloat().toDouble() }
            }
            TelemetryChannel(found.name, found.timestamps, values, "g", found.provenance)
        }
    }
    return AccelerationInG(converted, unit, true)
}

/** [accelerationInG] of the channel the alias [alias] names; null when it names none. */
fun accelerationAliasInG(session: TelemetrySession, alias: String): AccelerationInG? {
    val name = session.aliases[alias].orEmpty()
    return if (name.isEmpty()) null else accelerationInG(session, name)
}

/** "km/h" or "mph" for a speed unit as recordings write it, else empty. */
fun normalizedSpeedUnit(unit: String): String = when (unit.trim().lowercase()) {
    "km/h", "kmh", "kph", "km/hr" -> "km/h"
    "mph", "mi/h" -> "mph"
    else -> ""
}

/** Whether a channel called [name] is a speed (`speed`, `velocity`, `velocity-obd`, …). */
fun isSpeedChannel(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "speed" || lower.startsWith("velocity")
}

/** Whether channel [name] of [session] is a speed: named as one or the `speed` alias's channel. */
fun isSessionSpeedChannel(session: TelemetrySession, name: String) =
    isSpeedChannel(name) || session.aliases[Aliases.SPEED] == name

/** The speed unit [session] declares for [name]: "km/h" or "mph" however written, else as written. */
fun declaredSpeedUnit(session: TelemetrySession, name: String): String {
    val unit = declaredChannelUnit(session, name)
    return normalizedSpeedUnit(unit).ifEmpty { unit.trim() }
}

/**
 * The unit analysis reads channel [name] in: for a speed the declared unit, else [assumed] (the
 * unit the user assumes for unlabelled speeds); any other channel its declared unit.
 */
fun effectiveChannelUnit(session: TelemetrySession, name: String, assumed: String = ""): String {
    if (session.channels[name] == null) return ""
    if (!isSessionSpeedChannel(session, name)) return declaredChannelUnit(session, name)
    return declaredSpeedUnit(session, name).ifEmpty { normalizedSpeedUnit(assumed) }
}

/** Metres per second in one [unit] of speed (none is km/h, as Overlays reads it); null if unknown. */
fun metresPerSecondPerSpeedUnit(unit: String): Double? = when (unit.trim().lowercase()) {
    "", "km/h", "kmh", "kph", "km/hr" -> 1 / 3.6
    "mph", "mi/h" -> 0.44704
    "m/s" -> 1.0
    "kn", "kt", "knots" -> 0.514444
    else -> null
}

/**
 * Whether speeds in [a] and [b] can be subtracted as they are: the same unit however written, or
 * both without one. A declared unit and no unit differ.
 */
fun sameSpeedUnit(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    if (x.isEmpty() || y.isEmpty()) return x.isEmpty() && y.isEmpty()
    val known = normalizedSpeedUnit(x)
    return if (known.isNotEmpty()) known == normalizedSpeedUnit(y) else x.equals(y, ignoreCase = true)
}
