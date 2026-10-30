package com.flappedear.betterdriver.camera

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    private val stall = AtomicBoolean(false)
    private val stallReported = AtomicBoolean(false)

    /**
     * The writer was behind for a whole wait: nothing is queued from then on, also by the other
     * encoder's thread if its wait ends after this one's.
     */
    val stalled: Boolean get() = stall.get()

    /** KiB taken by copies waiting for the writer. */
    val takenKib: Int get() = budgetKib - room.availablePermits()

    /**
     * Room for a copy of [capacity] bytes; [onFull], if any, is called before waiting for it. False,
     * with the writer [stalled], when the room is still taken after [waitMs].
     */
    fun reserve(capacity: Int, onFull: (() -> Unit)? = null): Boolean {
        if (stall.get()) return false
        val kib = kibOf(capacity)
        val taken = room.tryAcquire(kib) || run {
            onFull?.invoke()
            room.tryAcquire(kib, waitMs, TimeUnit.MILLISECONDS)
        }
        if (!taken) stall.set(true)
        // Room freed after the other thread's wait ran out is not taken: the writer is stalled for both.
        val queued = taken && !stall.get()
        if (taken && !queued) room.release(kib)
        return queued
    }

    /** True for the one caller that reports the stall, false for every other and before a stall. */
    fun claimStallReport(): Boolean = stall.get() && stallReported.compareAndSet(false, true)

    /** The writer is done with a copy of [capacity] bytes that [reserve] made room for. */
    fun release(capacity: Int) = room.release(kibOf(capacity))

    /**
     * Runs the writer's [write] of a copy of [capacity] bytes, and gives its room back whatever
     * happens (audit 30 Oct BD-04): until now an exception in the write kept the room taken for good,
     * so the encoder later reported a stalled writer instead of the real failure, which went to [failed].
     */
    @Suppress("TooGenericExceptionCaught") // Any failure of the write is reported, never lost with the thread.
    fun written(capacity: Int, failed: (Exception) -> Unit, write: () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            failed(e)
        } finally {
            release(capacity)
        }
    }

    /** Whole KiB, at least one; a copy bigger than the whole budget takes all of it. */
    private fun kibOf(bytes: Int) = ((bytes + BytesPerKib - 1) / BytesPerKib).coerceIn(1, budgetKib)

    private companion object {
        const val BytesPerKib = 1_024
    }
}
