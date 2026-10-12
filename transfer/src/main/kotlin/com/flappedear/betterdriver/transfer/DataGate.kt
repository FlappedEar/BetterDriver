package com.flappedear.betterdriver.transfer

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Keeps the app's own writes (tracks, setup, coach settings and picks, imports) out of a restore
 * (FBBD-156). A restore copies a folder, merges into the copy and swaps it in; a write landing in
 * between would be lost. Writes run under [write] and wait while a restore holds [exclusive].
 *
 * A file kept whole in memory (the setup, the coach settings) is written with [writeIfCurrent]
 * instead: a value taken before or during a restore is stale once the restore has swapped the file,
 * so it is dropped and the app reads the restored file again.
 */
class DataGate {
    private val lock = ReentrantReadWriteLock()

    /** Goes up when a restore starts and again when it ends, so it is odd while one runs. */
    @Volatile
    var epoch = 0L
        private set

    /** Whether a restore runs now. */
    val restoring: Boolean get() = epoch % 2 == 1L

    /** Runs [block], waiting for a restore to end first. */
    fun <T> write(block: () -> T): T = lock.read(block)

    /**
     * Runs [block] if no restore started since [since] (an [epoch] read when the value to write was
     * taken); waits for one that runs. Returns whether [block] ran.
     */
    fun writeIfCurrent(since: Long, block: () -> Unit): Boolean = lock.read {
        val current = epoch == since && since % 2 == 0L
        if (current) block()
        current
    }

    /** Runs a restore: waits for writes under way, and holds new ones until it ends. */
    fun <T> exclusive(block: () -> T): T = lock.write {
        epoch++
        try {
            block()
        } finally {
            epoch++
        }
    }
}
