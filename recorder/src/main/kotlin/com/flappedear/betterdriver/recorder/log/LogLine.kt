package com.flappedear.betterdriver.recorder.log

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One line of the app's continuous log (FBBD-183): when it was written ([utcMillis], an instant) and
 * on the phone's boot clock ([uptimeMicros], which never jumps), which process ([process], [pid]) and
 * thread wrote it, its level and tag, the recording it belongs to ([session], null outside one) and
 * the message. The log is plain text, one line each, its time in the phone's own time zone with the
 * offset from UTC, so the time reads as the phone showed it and still names one instant (FBBD-217):
 *
 * `2026-10-12T12:36:08.593+02:00 4711.204518 recorder 2646 main I Recording [20261012-123607-3713ff1a]: message`
 *
 * Lines of builds before that end in `Z` (UTC) and are read the same way.
 *
 * A stack trace follows its line on lines of its own, which [parse] does not read as lines.
 */
data class LogLine(
    val utcMillis: Long,
    val uptimeMicros: Long?,
    val process: String,
    val pid: Int,
    val thread: String,
    val level: Char,
    val tag: String,
    val session: String?,
    val message: String,
) {
    fun format(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(timeText(utcMillis, zone)).append(' ')
        append(uptimeMicros?.let(::uptimeText) ?: "-").append(' ')
        append(process).append(' ').append(pid).append(' ').append(thread.replace(' ', '_')).append(' ')
        append(level).append(' ').append(tag).append(" [").append(session ?: NoSession).append("]: ")
        append(message)
    }

    companion object {
        private const val NoSession = "-"
        private const val MicrosPerSecond = 1_000_000L
        private const val MicroDigits = 6

        private const val Stamp = """(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}(?:Z|[+-]\d\d:\d\d))"""
        private val Current =
            Regex("""^$Stamp (\d+\.\d{6}|-) (\S+) (\d+) (\S+) ([DIWE]) (.*?) \[([^\]]*)]: (.*)$""")

        /** The per-process files of builds before the continuous log: no boot clock, process or session. */
        private val Legacy = Regex("""^$Stamp (\d+) (\S+) ([DIWE]) (.*?): (.*)$""")

        /** Always three fraction digits and an offset, `+00:00` for UTC itself (never `Z`). */
        private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

        /** [millis] as the log writes it: local time in [zone] with its offset from UTC. */
        fun timeText(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

        private fun uptimeText(micros: Long) =
            "${micros / MicrosPerSecond}.${(micros % MicrosPerSecond).toString().padStart(MicroDigits, '0')}"

        private fun utc(text: String): Long? = try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        /**
         * [text] as a log line, or null for a continuation (a stack trace) or anything else. A line of
         * an older build's file has no process of its own: [legacyProcess] names it (the file's name).
         */
        fun parse(text: String, legacyProcess: String = "main"): LogLine? =
            Current.matchEntire(text)?.let(::current) ?: Legacy.matchEntire(text)?.let { legacy(it, legacyProcess) }

        private fun current(m: MatchResult): LogLine? {
            val g = m.groupValues.listIterator(1)
            val stamp = g.next()
            val uptime = g.next()
            val process = g.next()
            val pid = g.next()
            val thread = g.next()
            val level = g.next()
            val tag = g.next()
            val session = g.next()
            val message = g.next()
            val micros = uptime.takeIf { it != NoSession }?.let {
                it.substringBefore('.').toLong() * MicrosPerSecond + it.substringAfter('.').toLong()
            }
            val named = session.takeIf { it != NoSession && it.isNotEmpty() }
            return utc(stamp)?.let { LogLine(it, micros, process, pid.toInt(), thread, level[0], tag, named, message) }
        }

        private fun legacy(m: MatchResult, process: String): LogLine? {
            val g = m.groupValues.listIterator(1)
            val stamp = g.next()
            val pid = g.next()
            val thread = g.next()
            val level = g.next()
            val tag = g.next()
            val message = g.next()
            return utc(stamp)?.let { LogLine(it, null, process, pid.toInt(), thread, level[0], tag, null, message) }
        }
    }
}
