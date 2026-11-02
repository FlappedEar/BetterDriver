package com.flappedear.betterdriver.recorder.store

/**
 * The same drive stored more than once (FBBD-427): a VBO or RaceChrono file imported twice made a
 * second session with a new id and the same data, so the Sessions list showed it twice. Android and
 * iOS decide it here, the same way.
 *
 * Two sessions are the same drive when they start at the same millisecond and last the same
 * microseconds. A new recording never matches an earlier one; an import of the same file always
 * matches the first import of it.
 */
object SessionCopies {
    /** Whether [a] and [b] are the same drive. A session still recording, or with no length yet, is never a copy. */
    fun sameDrive(a: StoredSession, b: StoredSession): Boolean =
        sameDrive(a.info.startUtcMillis, a.durationMicros, b) && a.state.settled() && b.state.settled()

    /** The session in [sessions] that one starting at [startUtcMillis] and lasting [durationMicros] repeats, if any. */
    fun copyOf(sessions: List<StoredSession>, startUtcMillis: Long, durationMicros: Long?): StoredSession? =
        sessions.firstOrNull { it.state.settled() && sameDrive(startUtcMillis, durationMicros, it) }

    /**
     * The extra copies in [sessions] that can go without losing anything: for each drive stored more
     * than once, every imported copy but one. The one kept is a recorded copy if there is one, else
     * one with video, else the earliest id. A recorded session, or one with video ([hasVideo]), is
     * never named, so the drive's only copy, and any copy holding more than its file, always stays.
     */
    fun extras(sessions: List<StoredSession>, hasVideo: (StoredSession) -> Boolean): List<StoredSession> =
        sessions.filter { it.state.settled() && it.durationMicros != null }
            .groupBy { it.info.startUtcMillis to it.durationMicros }
            .values
            .filter { it.size > 1 }
            .flatMap { copies ->
                val kept = copies.sortedWith(
                    compareBy<StoredSession> { it.info.imported != null }.thenBy { !hasVideo(it) }.thenBy { it.id },
                ).first()
                copies.filter { it !== kept && it.info.imported != null && !hasVideo(it) }
            }

    private fun sameDrive(startUtcMillis: Long, durationMicros: Long?, s: StoredSession) =
        durationMicros != null && s.info.startUtcMillis == startUtcMillis && s.durationMicros == durationMicros

    private fun SessionState.settled() = this == SessionState.FINISHED || this == SessionState.RECOVERED
}
