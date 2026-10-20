package com.flappedear.betterdriver.recording

import android.os.Bundle
import com.flappedear.betterdriver.obd.ObdLinkState
import com.flappedear.betterdriver.obd.ObdReading
import com.flappedear.betterdriver.obd.ObdStatus
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

    /**
     * The session receiver's Bluetooth address and advertised name: a RaceBox, or with
     * [EXTRA_GNSS_NMEA] a Bluetooth NMEA receiver (FBBD-108). Absent when there is none.
     */
    const val EXTRA_RACEBOX_ADDRESS = "racebox_address"
    const val EXTRA_RACEBOX_NAME = "racebox_name"

    /** True when the session uses Demo GNSS instead of a RaceBox (FBBD-83). */
    const val EXTRA_GNSS_DEMO = "gnss_demo"

    /** True when the receiver at [EXTRA_RACEBOX_ADDRESS] is a Bluetooth NMEA receiver, not a RaceBox (FBBD-108). */
    const val EXTRA_GNSS_NMEA = "gnss_nmea"

    /** The receiver's [GnssType] name; when absent (an older client), [EXTRA_GNSS_DEMO] and [EXTRA_GNSS_NMEA] say. */
    const val EXTRA_GNSS_TYPE = "gnss_type"

    /** Sensors to record too, each [SensorRef.encode] (FBBD-119). */
    const val EXTRA_SENSORS = "sensors"

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

    /** In a [VIDEO_SYNCED] report: the session time the newest frame was captured, when known (FBBD-21). */
    const val VIDEO_FRAME_US = "frameMicros"
    /** In a [VIDEO_STARTED] report: the frame rate the file was set to record at (FBBD-246). */
    const val VIDEO_FPS = "fps"
    const val VIDEO_TEXT = "text"
    const val VIDEO_OK = "ok"

    /** In a [VIDEO_THERMAL] report: the thermal status, and the headroom forecast (absent if unknown). */
    const val VIDEO_THERMAL_STATUS = "thermalStatus"
    const val VIDEO_HEADROOM = "headroom"

    /** In a [VIDEO_THERMAL] report: true when the camera ignores the phone's temperature (FBBD-231). */
    const val VIDEO_HEAT_IGNORED = "heatIgnored"

    /** Values of [VIDEO_KIND]. */
    const val VIDEO_STARTED = 1
    const val VIDEO_SYNCED = 2
    const val VIDEO_STOPPED = 3
    const val VIDEO_STATUS = 4
    const val VIDEO_THERMAL = 5

    /**
     * Coach to service (FBBD-98): an event to log in the running session, in msg.data with
     * [com.flappedear.betterdriver.coach.CoachProtocol] keys. Ignored unless its session is running.
     */
    const val MSG_COACH_EVENT = 5

    /** Coach to service: the coach line for the track screen. */
    const val MSG_COACH_STATUS = 6

    /**
     * UI to service (FBBD-183): an event of the drive's checks the UI process saw (the track screen's
     * frames, the GNSS banner, a GoPro's acknowledgement), to record in the running session so its
     * report has it too. In msg.data: [UI_SESSION], [UI_T_US], [UI_KIND], [UI_TEXT]. Only the kinds in
     * CheckLog.UiEventKinds; ignored unless its session is running.
     */
    const val MSG_UI_EVENT = 7
    const val UI_SESSION = "session"
    const val UI_T_US = "tMicros"
    const val UI_KIND = "kind"
    const val UI_TEXT = "text"

    /** In a [MSG_STATE] bundle: the coach line's corner label and text, each absent when empty (FBBD-98). */
    const val KEY_COACH_CORNER = "coachCorner"
    const val KEY_COACH_TEXT = "coachText"

    /** In a [MSG_STATE] bundle: true when the coach line shows a praise (FBBD-243). */
    const val KEY_COACH_PRAISE = "coachPraise"

    /** In a [MSG_STATE] bundle: the track or circuit the recording is at, once detected (FBBD-55). */
    const val KEY_PLACE = "place"

    /** In a [MSG_STATE] bundle: true when [KEY_PLACE] is a saved track, false for a circuit-list match. */
    const val KEY_PLACE_SAVED = "placeSaved"

    private const val OBD_STATE = "obd.state"
    private const val OBD_DETAIL = "obd.detail"
    private const val OBD_IDS = "obd.ids"
    private const val OBD_NAMES = "obd.names"
    private const val OBD_UNITS = "obd.units"
    private const val OBD_VALUES = "obd.values"
    private const val OBD_HZ = "obd.hz"

    private const val SESSION_ID = "sessionId"
    private const val START_UTC = "startUtcMillis"
    private const val ELAPSED = "elapsedMicros"
    private const val FIXES = "fixes"
    private const val FIX_RATE = "fixRateHz"
    private const val RECEIVER_RATE = "receiverRateHz"
    private const val FIX_AGE = "lastFixAgeMicros"
    private const val SATS = "sats"
    private const val SPEED = "speedKmh"
    private const val STORAGE_LOW = "storageLowBytes"
    private const val STOPPED = "stoppedReason"
    private const val DROPPED = "droppedSamples"
    private const val G_COMBINED = "gCombined"
    private const val CHANNEL_NAMES = "channelNames"
    private const val CHANNEL_VALUES = "channelValues"

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
    private const val POINT_TO_POINT = "timing.pointToPoint"

    fun toBundle(s: LiveSnapshot) = Bundle().apply {
        putString(SESSION_ID, s.sessionId)
        putLong(START_UTC, s.startUtcMillis)
        putLong(ELAPSED, s.elapsedMicros)
        putLong(FIXES, s.fixes)
        putInt(FIX_RATE, s.fixRateHz)
        putInt(RECEIVER_RATE, s.receiverRateHz)
        s.lastFixAgeMicros?.let { putLong(FIX_AGE, it) }
        s.sats?.let { putInt(SATS, it) }
        s.speedKmh?.let { putDouble(SPEED, it) }
        s.storageLowBytes?.let { putLong(STORAGE_LOW, it) }
        s.stoppedReason?.let { putString(STOPPED, it) }
        putLong(DROPPED, s.droppedSamples)
        s.gCombined?.let { putDouble(G_COMBINED, it) }
        if (s.channels.isNotEmpty()) {
            val names = s.channels.keys.toTypedArray()
            putStringArray(CHANNEL_NAMES, names)
            putDoubleArray(CHANNEL_VALUES, DoubleArray(names.size) { s.channels.getValue(names[it]) })
        }
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
            receiverRateHz = b.getInt(RECEIVER_RATE),
            channels = channels(b),
        )
    }

    private fun channels(b: Bundle): Map<String, Double> {
        val names = b.getStringArray(CHANNEL_NAMES) ?: return emptyMap()
        val values = b.getDoubleArray(CHANNEL_VALUES) ?: return emptyMap()
        if (names.size != values.size) return emptyMap()
        return names.indices.associate { names[it] to values[it] }
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
        putBoolean(POINT_TO_POINT, t.pointToPoint)
    }

    /** Adds the recording's OBD-II connection (FBBD-261) to a [MSG_STATE] bundle. */
    fun putObd(b: Bundle, s: ObdStatus) = b.apply {
        putString(OBD_STATE, s.state.name)
        putString(OBD_DETAIL, s.detail)
        putStringArray(OBD_IDS, s.readings.map { it.id }.toTypedArray())
        putStringArray(OBD_NAMES, s.readings.map { it.name }.toTypedArray())
        putStringArray(OBD_UNITS, s.readings.map { it.unit }.toTypedArray())
        putDoubleArray(OBD_VALUES, s.readings.map { it.value }.toDoubleArray())
        putDoubleArray(OBD_HZ, s.readings.map { it.hz }.toDoubleArray())
    }

    /** The recording's OBD-II connection, or null when the recording has no OBD source. */
    fun obdFrom(b: Bundle): ObdStatus? {
        val state = b.getString(OBD_STATE)?.let { name -> ObdLinkState.entries.firstOrNull { it.name == name } }
            ?: return null
        val ids = b.getStringArray(OBD_IDS).orEmpty()
        val names = b.getStringArray(OBD_NAMES).orEmpty()
        val units = b.getStringArray(OBD_UNITS).orEmpty()
        val values = b.getDoubleArray(OBD_VALUES) ?: DoubleArray(0)
        val hz = b.getDoubleArray(OBD_HZ) ?: DoubleArray(0)
        val n = minOf(ids.size, names.size, units.size, values.size, hz.size)
        val readings = (0 until n).map { ObdReading(ids[it], names[it], units[it], values[it], hz[it]) }
        return ObdStatus(state, b.getString(OBD_DETAIL).orEmpty(), readings)
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
            pointToPoint = b.getBoolean(POINT_TO_POINT, false),
        )
    }
}
