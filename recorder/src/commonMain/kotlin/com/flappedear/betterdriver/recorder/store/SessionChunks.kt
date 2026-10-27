package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo

/**
 * [def], read from a chunk (which stores no provenance), with its provenance from [info]: the
 * metadata's entry for its id, which the recorder keeps up to date for every channel it writes
 * (FBBD-174), else one derived as for a session stored before provenance was ([Provenance.forSession]).
 */
internal fun withProvenance(def: ChannelDef, info: SessionInfo): ChannelDef {
    val stored = info.channels.firstOrNull { it.id == def.id && it.name == def.name }?.provenance
    return def.copy(provenance = stored ?: Provenance.forSession(def, info.devices, info.imported))
}

/**
 * Session [id] from its metadata [info] and its chunk files' bytes in order ([chunks] reads the
 * next one, or null after the last), as SessionStore.read gives it on Android and iOS.
 * With [samples] false, the sample sets are left out; with them, a receiver's GNSS values are put
 * back at the time of their fix ([withGnssAtFixTime]).
 */
fun sessionDataOf(
    id: String,
    info: SessionInfo,
    state: SessionState,
    chunks: Sequence<ByteArray>,
    samples: Boolean = true,
): SessionData {
    val channels = LinkedHashMap<Int, ChannelDef>()
    info.channels.forEach { channels[it.id] = it }
    val sets = ArrayList<SampleSet>()
    val events = ArrayList<RecorderEvent>()
    var end: Record.End? = null
    var truncated = 0L
    var skipped = 0L
    for (bytes in chunks) {
        val r = Frames.scan(bytes) { rec ->
            when (rec) {
                is Record.Channel -> channels[rec.def.id] = withProvenance(rec.def, info)
                is Record.Samples -> if (samples) sets += rec.set
                is Record.Event -> events += rec.event
                is Record.End -> end = rec
            }
        }
        truncated += r.truncatedTailBytes
        skipped += r.skippedBytes
    }
    val data = SessionData(id, info, state, channels, sets, events, end, truncated, skipped)
    // The GNSS fix a receiver sends with its acceleration is older than it (FBBD-229): put it back at its time.
    return if (samples) data.withGnssAtFixTime() else data
}

/** What recovery found in session [id]'s chunks; chunk files are only read, never changed (FBBD-71). */
fun recoveryReportOf(id: String, chunks: List<() -> ByteArray>): RecoveryReport {
    var records = 0L
    var sets = 0L
    var last: Long? = null
    var truncated = 0L
    var skipped = 0L
    for (c in chunks) {
        val r = Frames.scan(c()) { rec ->
            if (rec is Record.Samples) {
                sets++
                last = maxOf(last ?: Long.MIN_VALUE, rec.set.tMicros)
            }
        }
        records += r.records
        truncated += r.truncatedTailBytes
        skipped += r.skippedBytes
    }
    return RecoveryReport(id, chunks.size, records, sets, last, truncated, skipped)
}

/** recovery.properties for [report], written at [nowUtcMillis]. */
fun recoveryEntries(report: RecoveryReport, nowUtcMillis: Long): Map<String, String> = linkedMapOf(
    "recoveredAtUtcMillis" to nowUtcMillis.toString(),
    "chunks" to report.chunks.toString(),
    "records" to report.records.toString(),
    "sampleSets" to report.sampleSets.toString(),
    "lastSampleMicros" to (report.lastSampleMicros?.toString() ?: ""),
    "truncatedBytes" to report.truncatedBytes.toString(),
    "skippedBytes" to report.skippedBytes.toString(),
)

/** finished.properties after a clean stop. */
fun finishedEntries(reason: String, endMicros: Long, droppedSamples: Long): Map<String, String> = linkedMapOf(
    "reason" to reason,
    "endMicros" to endMicros.toString(),
    "droppedSamples" to droppedSamples.toString(),
)

/** A session's length from finished.properties, else recovery.properties; null while recording. */
fun durationMicrosOf(finished: Map<String, String>?, recovery: Map<String, String>?): Long? =
    (finished?.get("endMicros") ?: recovery?.get("lastSampleMicros"))?.toLongOrNull()
