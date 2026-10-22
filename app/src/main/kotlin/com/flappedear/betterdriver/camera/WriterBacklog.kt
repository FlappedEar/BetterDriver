package com.flappedear.betterdriver.camera

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The memory [WideEncoder]'s copies may hold while they wait for the writer thread (audit A15): a
 * hard budget of [budgetKib], charged by each copy's real capacity, not by the sample's size.
 *
 * Until 1.7.1 a sample that found no room for [waitMs] was queued anyway, so a writer that stayed
 * stuck let the queue grow without limit. Now such a wait marks the writer [stalled] for good: every
 * later [reserve] says no at once, without waiting, so the encoder's callback is never held again and
 * the encoder hands its buffers straight back while the recording ends.
 */
internal class WriterBacklog(private val budgetKib: Int, private val waitMs: Long) {
    private val room = Semaphore(budgetKib)

    /** The writer was behind for a whole wait: nothing is queued from then on. */
    @Volatile
    var stalled = false
        private set

    /** KiB taken by copies waiting for the writer. */
    val takenKib: Int get() = budgetKib - room.availablePermits()

    /**
     * Room for a copy of [capacity] bytes; [onFull], if any, is called before waiting for it. False,
     * with the writer [stalled], when the room is still taken after [waitMs].
     */
    fun reserve(capacity: Int, onFull: (() -> Unit)? = null): Boolean {
        if (stalled) return false
        val kib = kibOf(capacity)
        val taken = room.tryAcquire(kib) || run {
            onFull?.invoke()
            room.tryAcquire(kib, waitMs, TimeUnit.MILLISECONDS)
        }
        if (!taken) stalled = true
        return taken
    }

    /** The writer is done with a copy of [capacity] bytes that [reserve] made room for. */
    fun release(capacity: Int) = room.release(kibOf(capacity))

    /** Whole KiB, at least one; a copy bigger than the whole budget takes all of it. */
    private fun kibOf(bytes: Int) = ((bytes + BytesPerKib - 1) / BytesPerKib).coerceIn(1, budgetKib)

    private companion object {
        const val BytesPerKib = 1_024
    }
}
