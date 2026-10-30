package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.analysis.data.provenanceText
import com.flappedear.betterdriver.analysis.recorded.HorizontalAccuracy
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData
import kotlin.math.ceil
import kotlin.math.roundToLong

// The channels part of the session validation report (FBBD-174): where each channel's values come
// from, and how far they can be trusted: its sample rate, its longest gap (the oldest a held value
// gets) and, for positions, the receiver's median horizontal accuracy.

private const val Median = 0.5
private const val CmPerMetre = 100.0

/** Accuracies are counted to this many centimetres (100 m); any worse counts as this. */
private const val MaxAccuracyCm = 10_000

/** Receivers' horizontal accuracy channels in metres, by preference, as analysis.recorded reads them. */
private val AccuracyChannels = HorizontalAccuracy

/** Positions, whose confidence includes the receiver's accuracy. */
private val PositionIds = setOf(GnssChannels.LAT.id, GnssChannels.LON.id)

/**
 * One information metric per channel the session holds, in id order: its sample rate as the value,
 * its provenance and confidence in [QualityMetric.how]. Basis MEASURED for a measured channel,
 * DERIVED for any other. An import also names its file's format, maker and rate.
 *
 * Streams over the samples with a few running figures per channel ([ChannelTimes], [Centimetres]),
 * so an import of millions of points with dozens of channels holds no list per value.
 */
internal fun channelsSection(data: SessionData): QualitySection {
    val times = HashMap<Int, ChannelTimes>()
    val accuracyId = AccuracyChannels.firstNotNullOfOrNull { name ->
        data.channels.values.firstOrNull { it.name == name }
    }?.id
    val accuracies = Centimetres()
    for (set in inTimeOrder(data.sampleSets)) {
        for (v in set.values) {
            times.getOrPut(v.channelId) { ChannelTimes() }.add(set.tMicros)
            if (v.channelId == accuracyId && v.value.isFinite()) accuracies.add(v.value)
        }
    }
    val accuracy = accuracies.median()
    val metrics = data.channels.values.sortedBy { it.id }.map { def ->
        channelMetric(def, times[def.id] ?: ChannelTimes(), accuracy.takeIf { def.id in PositionIds })
    }
    val notes = data.info.imported?.let { o ->
        val rate = o.sampleRateHz?.let { ", ${fixed(it, 1)} Hz" }.orEmpty()
        val maker = if (o.receiver.isEmpty()) "" else ", made by ${o.receiver}"
        listOf(QualityNote(0, "Imported from ${o.format} file ${o.fileName}$maker$rate"))
    }.orEmpty()
    return QualitySection("Channels", metrics, notes)
}

/** [sets] in time order: as they are when already so (the usual case), else a sorted copy of the references. */
private fun inTimeOrder(sets: List<SampleSet>): List<SampleSet> {
    for (i in 1 until sets.size) {
        if (sets[i].tMicros < sets[i - 1].tMicros) return sets.sortedBy { it.tMicros }
    }
    return sets
}

/** A channel's sample times as running figures, not a list. [add] takes times in order. */
internal class ChannelTimes {
    var count = 0
        private set
    var first = 0L
        private set
    var last = 0L
        private set

    /** The longest interval between two samples in a row, µs; 0 with fewer than two. */
    var maxGap = 0L
        private set

    fun add(tMicros: Long) {
        if (count == 0) first = tMicros else maxGap = maxOf(maxGap, tMicros - last)
        last = tMicros
        count++
    }

    /** Samples per second over the span from the first to the last; null without a span. */
    val rateHz: Double? get() = if (count < 2 || last == first) null else (count - 1) / seconds(last - first)
}

/**
 * Values in metres counted per whole centimetre from 0 to 100 m, for a median without keeping every
 * value. The report writes accuracy to the centimetre, so it reads as the exact median would; a value
 * outside counts as the nearest end, so whatever a file holds, the counts stay this size.
 */
internal class Centimetres {
    private val counts = IntArray(MaxAccuracyCm + 1)
    private var total = 0

    fun add(metres: Double) {
        counts[(metres * CmPerMetre).roundToLong().coerceIn(0L, MaxAccuracyCm.toLong()).toInt()]++
        total++
    }

    /** The median by nearest rank, as [nearestRank] takes it; null when empty. */
    fun median(): Double? {
        val rank = ceil(Median * total).toInt().coerceIn(1, maxOf(total, 1))
        var seen = 0
        for ((cm, n) in counts.withIndex()) {
            seen += n
            if (seen >= rank) return cm / CmPerMetre
        }
        return null
    }
}

private fun channelMetric(def: ChannelDef, t: ChannelTimes, accuracyMeters: Double?): QualityMetric {
    val basis = if (def.provenance.measured) Basis.MEASURED else Basis.DERIVED
    val how = buildString {
        append(provenanceText(def.provenance).replaceFirstChar { it.uppercase() })
        append(" (").append(def.provenance.name).append(")")
        if (t.count < 2) {
            append("; ").append(t.count).append(if (t.count == 1) " sample" else " samples")
        } else {
            append("; longest gap ${fixed(seconds(t.maxGap), 2)} s")
        }
        accuracyMeters?.let { append("; median horizontal accuracy ${fixed(it, 2)} m") }
    }
    return info("channel.${def.id}", def.name, t.rateHz, "Hz", basis, how)
}
