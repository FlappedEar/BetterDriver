package com.flappedear.betterdriver.recorder.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Always three fraction digits and an offset, `+00:00` for UTC itself (never `Z`). */
private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

/** [millis] as the log writes it: local time in [zone] with its offset from UTC. */
fun LogLine.Companion.timeText(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

/** This line as the log writes it, its time in [zone] (FBBD-217). */
fun LogLine.format(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
    append(LogLine.timeText(utcMillis, zone)).append(' ')
    append(uptimeMicros?.let(LogLine::uptimeText) ?: "-").append(' ')
    append(process).append(' ').append(pid).append(' ').append(thread.replace(' ', '_')).append(' ')
    append(level).append(' ').append(tag).append(" [").append(session ?: LogLine.NoSession).append("]: ")
    append(message)
}
