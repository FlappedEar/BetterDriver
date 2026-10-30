package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.SessionLaps

// What a session's row in Sessions says about it (UI-139 C), the same on Android and iOS: "Corolla ·
// 22 min · 14 laps · Video · OBD · HR" under its time, and its best lap beside it.

/**
 * A session's timed laps and the best one at the saved track it was recorded at, and whether car
 * data (OBD-II) and heart rate were recorded. Unknown until the session has been read.
 */
data class SessionFacts(
    val laps: Int = 0,
    val bestMillis: Long? = null,
    val obd: Boolean = false,
    val heartRate: Boolean = false,
)

/** The source of the OBD-II channels, and of the heart rate monitor's (HeartRateChannels.SOURCE). */
private const val ObdSource = "obd"
private const val HeartRateSource = "heart_rate"

/** Whether [data] recorded car data and heart rate: at least one value from each source. */
fun recordedFacts(data: SessionData): SessionFacts {
    val sourceOf = data.channels.values
        .filter { it.source == ObdSource || it.source == HeartRateSource }
        .associate { it.id to it.source }
    val found = HashSet<String>()
    val wanted = sourceOf.values.toSet()
    for (set in data.sampleSets) {
        if (found.size == wanted.size) break
        for (v in set.values) sourceOf[v.channelId]?.let(found::add)
    }
    return SessionFacts(obd = ObdSource in found, heartRate = HeartRateSource in found)
}

/** [this] with the timed laps of [laps] and the best of them. */
fun SessionFacts.withLaps(laps: SessionLaps): SessionFacts = copy(
    laps = laps.laps.count { it.lap.kind == LapKind.Timed },
    bestMillis = laps.best?.lap?.timeMillis,
)

/** Session length in whole minutes, for example "18 min"; "<1 min" below one minute. */
fun sessionLength(micros: Long): String {
    val minutes = micros.coerceAtLeast(0) / MicrosPerMinute
    return if (minutes == 0L) "<1 min" else "$minutes min"
}

private const val MicrosPerMinute = 60_000_000L

/** "Corolla · 22 min · 14 laps · Video · OBD · HR": the car, how long, and what was recorded. */
fun sessionRowDetail(car: String, durationMicros: Long?, facts: SessionFacts?, video: Boolean): String = listOfNotNull(
    car.ifBlank { null },
    durationMicros?.let(::sessionLength),
    facts?.laps?.takeIf { it > 0 }?.let { if (it == 1) "1 lap" else "$it laps" },
    "Video".takeIf { video },
    "OBD".takeIf { facts?.obd == true },
    "HR".takeIf { facts?.heartRate == true },
).joinToString(" · ")
