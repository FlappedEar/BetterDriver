package com.flappedear.betterdriver.recording

import android.os.Bundle
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.timing.live.LiveSector
import com.flappedear.betterdriver.timing.live.LiveTimingState
import com.flappedear.betterdriver.timing.live.sectorName

/**
 * Messages between the UI process and [RecorderService], which runs in its own process (FBBD-69).
 * Commands go as intents; live data comes back over a Messenger as [Bundle]s.
 */
internal object RecorderProtocol {
    const val ACTION_START = "com.flappedear.betterdriver.recording.START"
    const val ACTION_STOP = "com.flappedear.betterdriver.recording.STOP"
    const val EXTRA_CAR = "car"
    const val EXTRA_TRACK = "track"

    /** The paired RaceBox's address and advertised name; absent when none is paired. */
    const val EXTRA_RACEBOX_ADDRESS = "racebox_address"
    const val EXTRA_RACEBOX_NAME = "racebox_name"

    /** True when the session uses Demo GNSS instead of a RaceBox (FBBD-83). */
    const val EXTRA_GNSS_DEMO = "gnss_demo"

    /** Client to service: start sending updates to msg.replyTo. */
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2

    /** Service to client: msg.data is [toBundle] of the current recording, or empty when idle. */
    const val MSG_STATE = 3

    /** In a [MSG_STATE] bundle: why the last start failed, if it did. */
    const val KEY_ERROR = "error"

    /**
     * In a [MSG_STATE] bundle while recording: System.nanoTime() at session time 0. Cameras in other
     * processes use it to stamp their reports on the session clock (CLOCK_MONOTONIC is shared).
     */
    const val KEY_START_NANOS = "startNanos"

    /** In a [MSG_STATE] bundle: the camera's short status for the status strip, and whether it is OK. */
    const val KEY_CAMERA_STATUS = "cameraStatus"
    const val KEY_CAMERA_OK = "cameraOk"

    /**
     * Camera to service: one [com.flappedear.betterdriver.recorder.video.VideoSink] call, in msg.data.
     * Ignored unless [VIDEO_SESSION] is the running session.
     */
    const val MSG_VIDEO = 4

    const val VIDEO_SESSION = "session"
    const val VIDEO_CAMERA = "camera"
    const val VIDEO_KIND = "kind"
    const val VIDEO_FILE = "file"
    const val VIDEO_AT_US = "atMicros"
    const val VIDEO_MEDIA_US = "mediaMicros"
    const val VIDEO_TEXT = "text"
    const val VIDEO_OK = "ok"

    /** Values of [VIDEO_KIND]. */
    const val VIDEO_STARTED = 1
    const val VIDEO_SYNCED = 2
    const val VIDEO_STOPPED = 3
    const val VIDEO_STATUS = 4

    /** In a [MSG_STATE] bundle: the track or circuit the recording is at, once detected (FBBD-55). */
    const val KEY_PLACE = "place"

    private const val SESSION_ID = "sessionId"
    private const val START_UTC = "startUtcMillis"
    private const val ELAPSED = "elapsedMicros"
    private const val FIXES = "fixes"
    private const val FIX_RATE = "fixRateHz"
    private const val FIX_AGE = "lastFixAgeMicros"
    private const val SATS = "sats"
    private const val SPEED = "speedKmh"
    private const val STORAGE_LOW = "storageLowBytes"
    private const val STOPPED = "stoppedReason"
    private const val DROPPED = "droppedSamples"
    private const val G_COMBINED = "gCombined"

    private const val LAP = "timing.lap"
    private const val LAP_MS = "timing.lapMillis"
    private const val DELTA = "timing.delta"
    private const val PREDICTED = "timing.predicted"
    private const val LAST = "timing.last"
    private const val BEST = "timing.best"
    private const val THEORETICAL = "timing.theoretical"
    private const val VS_LAP = "timing.sectorsVsBestLap"
    private const val VS_SECTOR = "timing.sectorsVsBestSector"
    private const val CURRENT = "timing.currentSector"

    fun toBundle(s: LiveSnapshot) = Bundle().apply {
        putString(SESSION_ID, s.sessionId)
        putLong(START_UTC, s.startUtcMillis)
        putLong(ELAPSED, s.elapsedMicros)
        putLong(FIXES, s.fixes)
        putInt(FIX_RATE, s.fixRateHz)
        s.lastFixAgeMicros?.let { putLong(FIX_AGE, it) }
        s.sats?.let { putInt(SATS, it) }
        s.speedKmh?.let { putDouble(SPEED, it) }
        s.storageLowBytes?.let { putLong(STORAGE_LOW, it) }
        s.stoppedReason?.let { putString(STOPPED, it) }
        putLong(DROPPED, s.droppedSamples)
        s.gCombined?.let { putDouble(G_COMBINED, it) }
    }

    /** Null for an idle service (no session id). */
    fun fromBundle(b: Bundle): LiveSnapshot? {
        val id = b.getString(SESSION_ID) ?: return null
        return LiveSnapshot(
            sessionId = id,
            startUtcMillis = b.getLong(START_UTC),
            elapsedMicros = b.getLong(ELAPSED),
            fixes = b.getLong(FIXES),
            fixRateHz = b.getInt(FIX_RATE),
            lastFixAgeMicros = if (b.containsKey(FIX_AGE)) b.getLong(FIX_AGE) else null,
            sats = if (b.containsKey(SATS)) b.getInt(SATS) else null,
            speedKmh = if (b.containsKey(SPEED)) b.getDouble(SPEED) else null,
            storageLowBytes = if (b.containsKey(STORAGE_LOW)) b.getLong(STORAGE_LOW) else null,
            stoppedReason = b.getString(STOPPED),
            droppedSamples = b.getLong(DROPPED),
            gCombined = if (b.containsKey(G_COMBINED)) b.getDouble(G_COMBINED) else null,
        )
    }

    /** Adds live timing (FBBD-12) to a [MSG_STATE] bundle. Unknown values are left out, or NaN in the sector arrays. */
    fun putTiming(b: Bundle, t: LiveTimingState) = b.apply {
        putInt(LAP, t.lapNumber)
        t.lapMillis?.let { putLong(LAP_MS, it) }
        t.deltaSeconds?.let { putDouble(DELTA, it) }
        t.predictedMillis?.let { putLong(PREDICTED, it) }
        t.lastMillis?.let { putLong(LAST, it) }
        t.bestMillis?.let { putLong(BEST, it) }
        t.theoreticalBestMillis?.let { putLong(THEORETICAL, it) }
        putDoubleArray(VS_LAP, t.sectors.map { it.deltaVsBestLapSeconds ?: Double.NaN }.toDoubleArray())
        putDoubleArray(VS_SECTOR, t.sectors.map { it.deltaVsBestSectorSeconds ?: Double.NaN }.toDoubleArray())
        putInt(CURRENT, t.sectors.indexOfFirst { it.current })
    }

    /** Live timing from a [MSG_STATE] bundle, or null when the recording is not being timed. */
    fun timingFrom(b: Bundle): LiveTimingState? {
        if (!b.containsKey(LAP)) return null
        val vsLap = b.getDoubleArray(VS_LAP) ?: DoubleArray(0)
        val vsSector = b.getDoubleArray(VS_SECTOR) ?: DoubleArray(0)
        val current = b.getInt(CURRENT, -1)
        fun Double.known() = takeUnless { it.isNaN() }
        fun long(key: String) = if (b.containsKey(key)) b.getLong(key) else null
        return LiveTimingState(
            lapNumber = b.getInt(LAP),
            lapMillis = long(LAP_MS),
            deltaSeconds = if (b.containsKey(DELTA)) b.getDouble(DELTA) else null,
            predictedMillis = long(PREDICTED),
            lastMillis = long(LAST),
            bestMillis = long(BEST),
            theoreticalBestMillis = long(THEORETICAL),
            sectors = List(minOf(vsLap.size, vsSector.size)) { i ->
                LiveSector(sectorName(i), vsLap[i].known(), vsSector[i].known(), current = i == current)
            },
        )
    }
}
