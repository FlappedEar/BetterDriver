package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.GnssSecond
import com.flappedear.betterdriver.recorder.log.HeartRateWindow
import com.flappedear.betterdriver.recorder.log.LogLine

/**
 * One recording as the app's continuous log tells it (FBBD-183): its start on the phone's wall clock,
 * its end on the session clock, every event with its session time, its GNSS second by second, its
 * heart rate 10 s at a time ([heartRate], FBBD-225), and the lines of other processes written while
 * it ran ([lines]). [legacy] for a recording an older build logged, without GNSS seconds or session
 * ids; its RaceBox statistics lines ([raceBox]) then stand in for the GNSS seconds.
 */
class LoggedSession(val id: String) {
    var startUtcMillis: Long? = null
    var source: String? = null
    var endMicros: Long? = null
    var stopReason: String? = null
    var legacy: Boolean = false
    val events = mutableListOf<RecorderEvent>()
    val seconds = mutableListOf<GnssSecond>()
    val heartRate = mutableListOf<HeartRateWindow>()
    val lines = mutableListOf<LogLine>()

    /** RaceBox statistics lines of an older build: wall-clock time, messages received and missing. */
    val raceBox = mutableListOf<Triple<Long, Long, Long>>()

    /** Session time of a wall-clock time, when the start is known. */
    fun sessionMicros(utcMillis: Long): Long? = startUtcMillis?.let { (utcMillis - it) * MicrosPerMilli }

    /**
     * When it ended on the session clock: its logged end, or for a recording without a stop line (its
     * process ended) its last GNSS second or event.
     */
    fun inferredEndMicros(): Long =
        endMicros ?: maxOf(seconds.maxOfOrNull { it.tMicros } ?: 0L, events.maxOfOrNull { it.tMicros } ?: 0L)
}

/**
 * Reads the app's continuous log (FBBD-183) into its recordings. The log is whatever the app's
 * Share log gave: the continuous log, the per-process files of older builds, or both; lines are put
 * in time order first, so files may come in any order.
 */
object DriveLog {
    private val LegacyStart = Regex("""^(\S+) started: (.*)$""")
    private val LegacyEvent = Regex("""^(\S+) t=(-?\d+)ms (\S+) ?(.*)$""")
    private val LegacyStop = Regex("""^(\S+) stopped: (.*)$""")
    private val RaceBoxStats = Regex("""^(\d+) messages, (\d+) missing""")
    private val LegacyThermal = Regex("""^status=(\w+) """)
    private val StatusNames = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

    /** The lines of one file; [process] names its process when it is an older build's per-process file. */
    fun parse(text: Sequence<String>, process: String = "main"): List<LogLine> =
        text.mapNotNull { LogLine.parse(it, process) }.toList()

    /** The recordings in [lines], in the order they started. */
    fun sessions(lines: List<LogLine>): List<LoggedSession> {
        val byId = LinkedHashMap<String, LoggedSession>()
        var legacyOpen: LoggedSession? = null
        for (line in lines.sortedBy { it.utcMillis }) {
            val id = line.session
            if (id != null) {
                read(byId.getOrPut(id) { LoggedSession(id) }, line)
            } else {
                legacyOpen = legacy(byId, legacyOpen, line)
            }
        }
        return byId.values.filter { it.startUtcMillis != null || it.events.isNotEmpty() || it.seconds.isNotEmpty() }
    }

    private fun read(s: LoggedSession, line: LogLine) {
        when (line.tag) {
            CheckLog.SESSION -> {
                val f = CheckLog.fields(line.message)
                if (line.message.startsWith("start")) {
                    s.startUtcMillis = f["utc_ms"]?.toLongOrNull() ?: line.utcMillis
                    s.source = f["source"]
                } else if (line.message.startsWith("stop")) {
                    s.endMicros = f["t_us"]?.toLongOrNull()
                    s.stopReason = f["reason"]
                }
            }
            CheckLog.EVENT -> CheckLog.parseEvent(line.message)?.let { s.events += it }
            CheckLog.GNSS -> GnssSecond.parse(line.message)?.let { s.seconds += it }
            CheckLog.HEART_RATE -> HeartRateWindow.parse(line.message)?.let { s.heartRate += it }
            else -> s.lines += line
        }
    }

    /** A line of an older build, which named no session: its recorder's lines open and close one. */
    private fun legacy(byId: MutableMap<String, LoggedSession>, open: LoggedSession?, line: LogLine): LoggedSession? {
        val m = line.message
        val recording = line.tag == "Recording"
        val start = LegacyStart.matchEntire(m)?.takeIf { recording }
        val stop = LegacyStop.matchEntire(m)?.takeIf { recording }
        val event = LegacyEvent.matchEntire(m)?.takeIf { recording }
        return when {
            start != null -> byId.getOrPut(start.groupValues[1]) { LoggedSession(start.groupValues[1]) }.apply {
                legacy = true
                startUtcMillis = line.utcMillis
                source = start.groupValues[2]
            }
            stop != null -> {
                byId[stop.groupValues[1]]?.let { s ->
                    s.endMicros = s.sessionMicros(line.utcMillis)
                    s.stopReason = stop.groupValues[2]
                }
                null
            }
            event != null -> {
                val (sid, ms, kind) = event.destructured
                val text = event.groupValues.last()
                byId[sid]?.events?.add(RecorderEvent(ms.toLong() * MicrosPerMilli, kind, text))
                open
            }
            else -> open?.also { legacyLine(it, line) }
        }
    }

    /** A line of the session an older build is recording: RaceBox statistics and thermal status are read. */
    private fun legacyLine(open: LoggedSession, line: LogLine) {
        val m = line.message
        RaceBoxStats.find(m)?.takeIf { line.tag == "RaceBox" }?.let {
            open.raceBox += Triple(line.utcMillis, it.groupValues[1].toLong(), it.groupValues[2].toLong())
        }
        LegacyThermal.find(m)?.takeIf { line.tag == "Thermal" }?.let {
            val status = StatusNames.indexOf(it.groupValues[1])
            val at = open.sessionMicros(line.utcMillis)
            if (status >= 0 && at != null) open.events += RecorderEvent(at, EventKinds.THERMAL_STATUS, "status=$status")
        }
        open.lines += line
    }
}
