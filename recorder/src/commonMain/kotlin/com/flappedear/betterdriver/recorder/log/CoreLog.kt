package com.flappedear.betterdriver.recorder.log

/**
 * An information line from common code, tagged as DebugLog's: on the JVM it goes to [DebugLog], so
 * the Android app's continuous log keeps it; iOS prints it.
 */
expect fun logInfo(tag: String, message: String)

/**
 * A line of the continuous log from common code, so both apps write the same lines (FBBD-356): on the
 * JVM [DebugLog], on iOS the app's log (AppLog.swift, through [IosLog]). [level] is one of
 * [CoreLog]'s; [session] names the recording the line belongs to, null for the one being recorded.
 */
expect fun logLine(level: Int, tag: String, message: String, error: Throwable? = null, session: String? = null)

/** The log's levels, as Android's Log priorities, and the lines common code writes for a recording. */
object CoreLog {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    /** A line for recording [session], whichever recording the app is following. */
    fun forSession(session: String, tag: String, message: String) = logLine(INFO, tag, message, null, session)

    /** The level's letter in a line ([LogLine]'s format). */
    fun levelChar(level: Int) = when (level) {
        DEBUG -> 'D'
        INFO -> 'I'
        WARN -> 'W'
        else -> 'E'
    }
}

/**
 * [value] with [digits] decimals, as `String.format(Locale.ROOT, "%.<digits>f")` writes it on the
 * JVM, for the log's `key=value` lines.
 */
expect fun fixedDecimals(value: Double, digits: Int): String
