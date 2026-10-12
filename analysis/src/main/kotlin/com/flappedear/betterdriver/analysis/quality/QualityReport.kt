package com.flappedear.betterdriver.analysis.quality

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bumped whenever what the report holds or how it is worked out changes; an older stored report is made again. */
const val QualityFormat = 1

/** A value against its pass criterion (FBBD-183). */
@Serializable
enum class Verdict {
    PASS,
    FAIL,

    /** The session does not record what the value needs, or had nothing to measure (no reconnect, no cue). */
    NOT_MEASURED,

    /** Shown for information; it has no pass criterion. */
    INFO,
}

/** Where a value comes from. */
@Serializable
enum class Basis {
    /** Read straight from what the session recorded: sample times, logged events. */
    MEASURED,

    /** Worked out from recorded values by the method [QualityMetric.how] states: a heuristic or an estimate. */
    DERIVED,

    /** No value. */
    NONE,
}

/**
 * One value of the session validation report (FBBD-183). [value] is in [unit], null when not
 * measured. [criterion] is the pass criterion with the issue it comes from, null for information.
 * [how] says how the value was obtained, or why it could not be.
 */
@Serializable
data class QualityMetric(
    val id: String,
    val label: String,
    val value: Double?,
    val unit: String,
    val verdict: Verdict,
    val basis: Basis,
    val criterion: String?,
    val how: String,
)

/** Something that happened at session time [tMicros], such as a gap or a reconnect. */
@Serializable
data class QualityNote(val tMicros: Long, val text: String)

/** One part of the report: GNSS, timing, recording, video, coach or thermal. */
@Serializable
data class QualitySection(
    val title: String,
    val metrics: List<QualityMetric>,
    val notes: List<QualityNote> = emptyList(),
)

/**
 * The validation report of one session (FBBD-183): the P0 pass criteria (FBBD-8, FBBD-12, FBBD-21,
 * FBBD-27, FBBD-43, FBBD-46, FBBD-72) measured from what the session recorded, each marked PASS,
 * FAIL or NOT_MEASURED. Stored as JSON next to the session ([toJson]).
 */
@Serializable
data class QualityReport(
    val format: Int = QualityFormat,
    val sessionId: String,
    /** The session's state when the report was made: FINISHED or RECOVERED. */
    val sessionState: String,
    val computedAtUtcMillis: Long,
    val sections: List<QualitySection>,
) {
    val metrics: List<QualityMetric> get() = sections.flatMap { it.metrics }

    fun count(verdict: Verdict): Int = metrics.count { it.verdict == verdict }

    fun toJson(): String = QualityJson.encodeToString(serializer(), this)

    companion object {
        /** The report in [json], or null if it is not one this version reads. */
        fun fromJson(json: String): QualityReport? = try {
            QualityJson.decodeFromString(serializer(), json).takeIf { it.format == QualityFormat }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private val QualityJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
