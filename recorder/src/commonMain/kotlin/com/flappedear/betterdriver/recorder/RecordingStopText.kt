package com.flappedear.betterdriver.recorder

/**
 * What the app says when a recording ended other than by a clean user stop, on both apps (review
 * v1.34.1 F9, FBBD-478). A storage stop writes its end and is saved; a write failure ("error") is
 * not: only what reached the disk is kept, and recovery picks it up at the next start. Before, Android
 * said "The session is saved." for a write failure, and iOS dismissed a failed STOP as a normal one.
 */
object RecordingStopText {
    const val STORAGE_FULL = "storage_full"
    const val ERROR = "error"

    /** The message for a recording that ended for [reason]; null for a clean user stop. */
    fun ended(reason: String): String? = when (reason) {
        "user" -> null
        STORAGE_FULL -> "Storage full. The session is saved."
        ERROR -> "Recording stopped: a write failed. What was written is kept and recovered at the next start."
        else -> "Recording stopped ($reason). The session is saved."
    }
}
