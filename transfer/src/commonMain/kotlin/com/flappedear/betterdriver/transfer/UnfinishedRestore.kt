package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.IOException

/**
 * Whether the app may read and write its data after a restore (review v1.34.1 F2, FBBD-476), as
 * Android's Backups.finishPending: a restore that failed after its commit marker leaves the data
 * part old, part restored. Nothing may read or change it (no recording, no edits, no other restore)
 * until [BackupRestorer.finishPending] completes it, or a retry could delete a track saved in the
 * meantime: finishing moves the old copy aside and deletes whatever is live when both exist.
 *
 * [finish] runs [BackupRestorer.finishPending] up to [tries] times and answers whether the data is
 * usable: true when no committed restore is pending (any more), false when it still is.
 */
class UnfinishedRestore(private val restorer: BackupRestorer, private val tries: Int = Tries) {
    /** True while a committed restore is not finished: the app shows only the unfinished-restore screen. */
    val blocked: Boolean get() = restorer.pending

    /** Finishes a pending restore; returns whether the data may be used. [log] gets each failure. */
    fun finish(log: (attempt: Int, error: Throwable) -> Unit = { _, _ -> }): Boolean {
        var attempt = 0
        while (restorer.pending && attempt < tries) {
            attempt++
            try {
                restorer.finishPending()
            } catch (e: IOException) {
                log(attempt, e)
            }
        }
        return !restorer.pending
    }

    private companion object {
        const val Tries = 3
    }
}
