package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.track.Track

/**
 * One video file of the session as its clip index (`:video`) has it: media time 0 is session time
 * [startMicros], found by [method] with a stated [uncertaintyMicros]. [endMicros] is null when unknown.
 */
data class QualityClip(
    val camera: String,
    val file: String,
    val startMicros: Long,
    val endMicros: Long?,
    val method: String,
    val uncertaintyMicros: Long,
)

/**
 * The session a recorder restart carried on from (FBBD-72): when it started, and its last GNSS sample
 * on its session clock and on the receiver's clock (UTC ms), each null when it has none.
 */
data class PreviousSession(
    val id: String,
    val startUtcMillis: Long,
    val lastSampleMicros: Long?,
    val lastUtcMillis: Long?,
)

/** [data] as the session a restart carried on from. */
fun previousSession(data: SessionData): PreviousSession {
    val s = GnssSamples(data.sampleSets)
    val lastUtc = s.utc.lastOrNull { it != GnssSamples.NoUtc }
    return PreviousSession(data.id, data.info.startUtcMillis, s.t.lastOrNull(), lastUtc)
}

/** The recorder's source name of a RaceBox receiver (core-ble RaceBoxSource.name). */
private const val RaceBoxSourceName = "racebox"

/** A channel only the RaceBox Micro has: the Micro skips its whole-second message without a fix too. */
private const val RaceBoxMicroChannel = "racebox_input"

/**
 * The validation report of a session (FBBD-183): the P0 pass criteria measured from what it
 * recorded, and where each channel came from with how far it can be trusted (FBBD-174). [track]
 * is its saved track, for laps replayed when live timing logged none; [clips] its video index;
 * [previous] the session a recorder restart carried on from, when it names one. Pure: reading the
 * session and its files is the caller's.
 */
@Suppress("LongParameterList") // Each is something the report is made from.
fun sessionQuality(
    data: SessionData,
    track: Track? = null,
    clips: List<QualityClip> = emptyList(),
    previous: PreviousSession? = null,
    computedAtUtcMillis: Long = 0L,
    frames: List<FileFrames> = emptyList(),
): QualityReport {
    val samples = GnssSamples(data.sampleSets)
    val lastEvent = data.events.maxOfOrNull { it.tMicros } ?: 0L
    val end = data.end?.tMicros ?: maxOf(samples.t.lastOrNull() ?: 0L, lastEvent)
    val gnss = gnssSection(
        samples,
        data.events,
        end,
        raceBox = RaceBoxSourceName in data.info.devices,
        skipsWithoutFix = data.channels.values.any { it.name == RaceBoxMicroChannel },
    )
    // Before its first fix a receiver's time is its own guess (2026-10-18 log): those samples are left out.
    val lags = (0 until samples.size).filter { samples.utc[it] != GnssSamples.NoUtc && samples.hasFix(it) }.map {
        samples.t[it] to samples.t[it] - (samples.utc[it] - data.info.startUtcMillis) * MicrosPerMilli
    }
    val demo = Provenance.DemoDevice in data.info.devices
    val sections = listOfNotNull(
        if (demo) demoGnssSection() else gnss.copy(metrics = gnss.metrics + clockDrift(lags, end)),
        timingSection(data, track),
        recordingSection(data, samples, previous),
        videoSection(data.events, clips, samples.t.firstOrNull() ?: 0L, samples.t.lastOrNull() ?: end, frames),
        goProSection(data.events, clips, end),
        coachSection(data.events, samples),
        thermalSection(data.events, end),
        screenSection(data.events, outages(samples, end), end, underCoverFrom(samples)),
        exportSection(data.events, end),
        channelsSection(data),
    )
    return QualityReport(QualityFormat, data.id, data.state.name, computedAtUtcMillis, withSoak(sections, end))
}

/**
 * The GNSS section of a session on Demo GNSS or the demo track (FBBD-233): its positions are made
 * up, so nothing about a receiver is measured, and such a session is never receiver evidence.
 */
internal fun demoGnssSection(): QualitySection {
    // The criterion the rate is judged by with a real receiver.
    val criterion = ">= 24 Hz over the session (FBBD-8)"
    val why = "demo receiver: the positions are made up by the app, so the receiver checks need a real one"
    return QualitySection("GNSS", listOf(notMeasured("gnss.rate", "Average sample rate", criterion, why)))
}

/** [sections] with the soak (FBBD-46) after them, judged from their values. */
internal fun withSoak(sections: List<QualitySection>, durationMicros: Long): List<QualitySection> =
    sections + soakSection(sections, durationMicros)
