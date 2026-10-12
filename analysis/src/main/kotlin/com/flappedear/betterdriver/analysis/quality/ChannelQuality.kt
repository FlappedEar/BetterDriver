package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.store.SessionData
import java.util.Locale

// The channels part of the session validation report (FBBD-174): where each channel's values come
// from, and how far they can be trusted: its sample rate, its longest gap (the oldest a held value
// gets) and, for positions, the receiver's median horizontal accuracy.

private const val Median = 0.5

/** Receivers' horizontal accuracy channels in metres, by preference (as analysis.recorded reads them). */
private val AccuracyChannels = listOf("gnss_hacc", "dragy_hacc")

/** Positions, whose confidence includes the receiver's accuracy. */
private val PositionIds = setOf(GnssChannels.LAT.id, GnssChannels.LON.id)

/** How each provenance is written in the report. */
internal fun provenanceText(p: Provenance): String = when (p) {
    Provenance.MEASURED_GPS -> "measured by GPS"
    Provenance.MEASURED_GNSS_RECEIVER -> "measured by the GNSS receiver"
    Provenance.MEASURED_ACCELEROMETER -> "measured by the accelerometer"
    Provenance.MEASURED_OBD -> "measured through OBD"
    Provenance.MEASURED_CAN -> "measured on the CAN bus"
    Provenance.MEASURED_SENSOR -> "measured by a sensor"
    Provenance.CALCULATED -> "calculated"
    Provenance.INFERRED -> "inferred"
    Provenance.FUSED -> "fused from several sources"
    Provenance.INTERPOLATED -> "interpolated"
    Provenance.SIMULATED -> "simulated"
    Provenance.UNKNOWN -> "of unknown source"
}

/**
 * One information metric per channel the session holds, in id order: its sample rate as the value,
 * its provenance and confidence in [QualityMetric.how]. Basis MEASURED for a measured channel,
 * DERIVED for any other. An import also names its file's format, maker and rate.
 */
internal fun channelsSection(data: SessionData): QualitySection {
    val times = HashMap<Int, MutableList<Long>>()
    val accuracyId = AccuracyChannels.firstNotNullOfOrNull { name ->
        data.channels.values.firstOrNull { it.name == name }
    }?.id
    val accuracies = mutableListOf<Double>()
    for (set in data.sampleSets) {
        for (v in set.values) {
            times.getOrPut(v.channelId) { mutableListOf() } += set.tMicros
            if (v.channelId == accuracyId && v.value.isFinite()) accuracies += v.value
        }
    }
    val accuracy = nearestRank(accuracies, Median)
    val metrics = data.channels.values.sortedBy { it.id }.map { def ->
        channelMetric(def, times[def.id].orEmpty().sorted(), accuracy.takeIf { def.id in PositionIds })
    }
    val notes = data.info.imported?.let { o ->
        val rate = o.sampleRateHz?.let { String.format(Locale.ROOT, ", %.1f Hz", it) }.orEmpty()
        val maker = if (o.receiver.isEmpty()) "" else ", made by ${o.receiver}"
        listOf(QualityNote(0, "Imported from ${o.format} file ${o.fileName}$maker$rate"))
    }.orEmpty()
    return QualitySection("Channels", metrics, notes)
}

private fun channelMetric(def: ChannelDef, t: List<Long>, accuracyMeters: Double?): QualityMetric {
    val basis = if (def.provenance.measured) Basis.MEASURED else Basis.DERIVED
    val how = buildString {
        append(provenanceText(def.provenance).replaceFirstChar { it.uppercase() })
        append(" (").append(def.provenance.name).append(")")
        if (t.size < 2) {
            append("; ").append(t.size).append(if (t.size == 1) " sample" else " samples")
        } else {
            val gap = t.zipWithNext { a, b -> b - a }.max()
            append(String.format(Locale.ROOT, "; longest gap %.2f s", seconds(gap)))
        }
        accuracyMeters?.let { append(String.format(Locale.ROOT, "; median horizontal accuracy %.2f m", it)) }
    }
    val rate = if (t.size < 2 || t.last() == t.first()) null else (t.size - 1) / seconds(t.last() - t.first())
    return info("channel.${def.id}", def.name, rate, "Hz", basis, how)
}
