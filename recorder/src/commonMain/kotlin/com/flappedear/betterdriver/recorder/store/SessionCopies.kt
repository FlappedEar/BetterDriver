package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.recorder.SessionInfo

/**
 * The same drive stored more than once (FBBD-427): a VBO or RaceChrono file imported twice made a
 * second session with a new id and the same data, so the Sessions list showed it twice. Android and
 * iOS decide it here, the same way.
 *
 * Two sessions are copies when they start at the same millisecond, last the same microseconds, have
 * the same channels and their chunk files are the same byte for byte. Two exports of one drive that
 * differ (one with engine RPM, one without) are not copies: both stay.
 */
object SessionCopies {
    /**
     * The session in [sessions] that the finished session in folder [dir], with [info] and lasting
     * [durationMicros], repeats, if any. A session still recording, or with no length yet, is never one.
     */
    fun copyOf(
        sessions: List<StoredSession>,
        dir: String,
        info: SessionInfo,
        durationMicros: Long?,
    ): StoredSession? =
        sessions.firstOrNull {
            it.settled() && durationMicros != null && it.durationMicros == durationMicros &&
                it.info.startUtcMillis == info.startUtcMillis && it.info.channels == info.channels &&
                sameData(it.dir, dir)
        }

    /**
     * The extra copies in [sessions] that can go without losing anything: for each drive stored more
     * than once, every imported copy whose data is the kept one's ([same], byte for byte by default).
     * The one kept is a recorded copy if there is one, else one with video, else the earliest id. A
     * recorded session, or one with video ([hasVideo]), is never named, so the drive's only copy, and
     * any copy holding more than another, always stays.
     */
    fun extras(
        sessions: List<StoredSession>,
        hasVideo: (StoredSession) -> Boolean,
        same: (kept: StoredSession, copy: StoredSession) -> Boolean = { a, b -> sameData(a.dir, b.dir) },
    ): List<StoredSession> =
        sessions.filter { it.settled() && it.durationMicros != null }
            .groupBy { it.info.startUtcMillis to it.durationMicros }
            .values
            .filter { it.size > 1 }
            .flatMap { copies ->
                val kept = copies.sortedWith(
                    compareBy<StoredSession> { it.info.imported != null }.thenBy { !hasVideo(it) }.thenBy { it.id },
                ).first()
                copies.filter {
                    it !== kept && it.info.imported != null && !hasVideo(it) &&
                        it.info.channels == kept.info.channels && same(kept, it)
                }
            }

    /** Whether the sessions in folders [a] and [b] hold the same chunk files, byte for byte. */
    fun sameData(a: String, b: String): Boolean {
        val chunksA = chunks(a)
        val chunksB = chunks(b)
        return chunksA.isNotEmpty() && chunksA.size == chunksB.size &&
            chunksA.zip(chunksB).all { (x, y) ->
                CoreFiles.size(x) == CoreFiles.size(y) && CoreFiles.readBytes(x).contentEquals(CoreFiles.readBytes(y))
            }
    }

    private fun chunks(dir: String) =
        CoreFiles.list(dir).filter(SessionFileNames::isChunk).sorted().map { CoreFiles.join(dir, it) }

    private fun StoredSession.settled() = state == SessionState.FINISHED || state == SessionState.RECOVERED
}
