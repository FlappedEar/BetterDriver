package com.flappedear.betterdriver.recorder.log

/**
 * How the app started and how its processes ended, as the continuous log keeps it (FBBD-41), so the
 * drive's checks can grade cold start and ANRs from the log alone. The lines, outside any recording:
 * - [STARTUP] "cold ms=<process start to the first frame>": a cold start of the app's screen.
 * - [EXIT] "check sdk=<API level> new=<count>" each time Android's record of how the app's processes
 *   ended was read (at the app's start and when a recording ends), or "check sdk=<API level>
 *   unavailable" before Android 11, which keeps no such record, or "check sdk=<API level> failed" when
 *   Android refused the read.
 * - [EXIT] "exit utc_ms=<when it ended> pid=<pid> process=<name> reason=<ANR, CRASH, ...>
 *   importance=<when it ended> desc=<Android's description>", one per process end not logged before.
 */
object AppLifeLog {
    const val STARTUP = "Startup"
    const val EXIT = "Exit"

    /** The [STARTUP] message of a cold start that took [millis] to its first frame. */
    fun coldStart(millis: Long) = "cold ms=$millis"

    /** The time a [STARTUP] message gives for a cold start, or null for any other message. */
    fun parseColdStart(message: String): Long? =
        if (message.startsWith("cold ")) CheckLog.fields(message)["ms"]?.toLongOrNull() else null

    /** The [EXIT] message of a read of Android's exit record on API [sdk]: [new] ends, null when it keeps none. */
    fun check(sdk: Int, new: Int?) = if (new == null) "check sdk=$sdk unavailable" else "check sdk=$sdk new=$new"

    /** The [EXIT] message of a read of Android's exit record on API [sdk] that Android refused. */
    fun checkFailed(sdk: Int) = "check sdk=$sdk failed"

    /**
     * Whether an [EXIT] "check" [message] read the record (true), could not (false: none kept, or the
     * read failed), or is not a check (null). Only a read that counted the new ends is a read.
     */
    fun parseCheck(message: String): Boolean? =
        if (message.startsWith("check ")) CheckLog.fields(message)["new"]?.toIntOrNull() != null else null

    /** The [EXIT] message of one process end. */
    fun exit(e: ProcessExit) = buildString {
        append("exit utc_ms=").append(e.utcMillis)
        append(" pid=").append(e.pid)
        append(" process=").append(e.process.ifBlank { "-" }.replace(' ', '_'))
        append(" reason=").append(e.reason.replace(' ', '_'))
        append(" importance=").append(e.importance)
        // Last: it may hold spaces. One line, always.
        append(" desc=").append(e.description.replace('\n', ' ').replace('\r', ' ').trim())
    }

    /** The process end an [EXIT] "exit" [message] holds, or null. */
    fun parseExit(message: String): ProcessExit? {
        if (!message.startsWith("exit ")) return null
        val at = message.indexOf(" desc=")
        val head = if (at < 0) message else message.substring(0, at)
        val f = CheckLog.fields(head)
        val utc = f["utc_ms"]?.toLongOrNull()
        val pid = f["pid"]?.toIntOrNull()
        val reason = f["reason"]
        return if (utc == null || pid == null || reason == null) {
            null
        } else {
            val desc = if (at < 0) "" else message.substring(at + " desc=".length)
            ProcessExit(utc, pid, f["process"].orEmpty(), reason, f["importance"]?.toIntOrNull() ?: 0, desc)
        }
    }
}

/**
 * One end of an app process as Android recorded it (ApplicationExitInfo, API 30+): when ([utcMillis]),
 * which process, why ([reason]: "ANR", "CRASH", "LOW_MEMORY", ...), its importance then, and
 * Android's own [description].
 */
data class ProcessExit(
    val utcMillis: Long,
    val pid: Int,
    val process: String,
    val reason: String,
    val importance: Int,
    val description: String,
) {
    companion object {
        const val ANR = "ANR"
    }
}
