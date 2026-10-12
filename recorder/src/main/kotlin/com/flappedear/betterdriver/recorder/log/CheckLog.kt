package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent

/**
 * The lines of the continuous log that the hardware checks are worked out from (FBBD-183): what each
 * says and how it is read back. The tags:
 * - [SESSION]: a recording started ("start utc_ms=<its start> source=<receiver>") or stopped
 *   ("stop t_us=<session time> reason=<why>").
 * - [EVENT]: an event of a recording, as stored in it: "t_us=<session time> <kind> <text>".
 * - [GNSS]: the GNSS samples of one second of a recording ([GnssSecond]).
 * - [QUALITY]: the session's own report at its end, one line per value.
 */
object CheckLog {
    const val SESSION = "Session"
    const val EVENT = "Event"
    const val GNSS = "Gnss"
    const val QUALITY = "Quality"

    /** Event kinds the UI process logs itself, so the recorder does not log them a second time. */
    val UiEventKinds = setOf(EventKinds.UI_FRAMES, EventKinds.GNSS_BANNER, EventKinds.GOPRO)

    private const val TimeKey = "t_us="

    fun eventText(tMicros: Long, kind: String, text: String) = "$TimeKey$tMicros $kind $text".trimEnd()

    /** Logs an event of recording [session], at session time [tMicros]. */
    fun event(session: String, tMicros: Long, kind: String, text: String) =
        DebugLog.forSession(session, EVENT, eventText(tMicros, kind, text))

    /** The event an [EVENT] line's message holds, or null. */
    fun parseEvent(message: String): RecorderEvent? {
        val parts = message.removePrefix(TimeKey).split(' ', limit = EventParts)
        val t = parts[0].toLongOrNull()
        val kind = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return if (message.startsWith(TimeKey) && t != null && kind != null) {
            RecorderEvent(t, kind, parts.getOrElse(2) { "" })
        } else {
            null
        }
    }

    private const val EventParts = 3

    /** The `key=value` fields of a line's message; a value ends at the next space. */
    fun fields(text: String): Map<String, String> = text.split(' ').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }.toMap()
}
