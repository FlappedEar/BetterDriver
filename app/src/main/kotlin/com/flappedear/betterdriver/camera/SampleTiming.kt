package com.flappedear.betterdriver.camera

import java.util.Locale

/**
 * How long encoded video samples spend between the encoder and the MP4 (FBBD-278), for one log line a
 * minute. Arek (2026-10-21): an encoder whose output buffers are held while the muxer writes can stop
 * taking frames, and a camera with no free buffer skips frames; the line says whether that happens.
 *
 * `encoder timing: samples=3600 encode_ms=20.1/25.0/40.2 hold_ms=0.1/0.2/1.5 queue_ms=0.1/0.4/3.0
 * lock_ms=0.0/0.0/0.2 write_ms=0.1/0.3/12.0 backlog_max=2 samples 0.1 MB full_waits=0`: per video
 * sample, p50/p95/max of the time from the frame (its PTS) to the encoder's callback for it ([encoded]),
 * how long the callback held the encoder's buffer ([held]: copy, then release; [released] without MP4),
 * how long the copy waited for the writer thread, for the muxer's lock and in writeSampleData
 * ([written]); the most samples and bytes waiting at once, and how often the encoder waited because the
 * writer was that far behind ([fullWait]). Each stage is apart, so a delay shows where it arises.
 */
class SampleTiming {
    private val encode = Spread()
    private val hold = Spread()
    private val queue = Spread()
    private val lock = Spread()
    private val write = Spread()
    private var backlog = 0
    private var backlogBytes = 0L
    private var maxBacklog = 0
    private var maxBacklogBytes = 0L
    private var fullWaits = 0

    /** The encoder's callback for a frame began [nanos] after the frame's time (its PTS). */
    @Synchronized
    fun encoded(nanos: Long) {
        encode.add(nanos.coerceAtLeast(0))
    }

    /** With no MP4: the encoder's buffer went back to it [nanos] after its callback began. */
    @Synchronized
    fun released(nanos: Long) {
        hold.add(nanos)
    }

    /** The encoder's output buffer was held [nanos], then [bytes] went into the writer's queue. */
    @Synchronized
    fun held(nanos: Long, bytes: Int) {
        hold.add(nanos)
        backlog++
        backlogBytes += bytes
        maxBacklog = maxOf(maxBacklog, backlog)
        maxBacklogBytes = maxOf(maxBacklogBytes, backlogBytes)
    }

    /** The encoder waited for the writer, whose queue was full. */
    @Synchronized
    fun fullWait() {
        fullWaits++
    }

    /**
     * A sample of [bytes] left the queue after [queueNanos], got the muxer's lock in [lockNanos] and was
     * written in [writeNanos].
     */
    @Synchronized
    fun written(bytes: Int, queueNanos: Long, lockNanos: Long, writeNanos: Long) {
        queue.add(queueNanos)
        lock.add(lockNanos)
        write.add(writeNanos)
        backlog = (backlog - 1).coerceAtLeast(0)
        backlogBytes = (backlogBytes - bytes).coerceAtLeast(0)
    }

    /** The line since the last one, and a fresh start; null when the encoder gave no sample. */
    @Synchronized
    fun line(): String? {
        if (encode.count == 0) return null
        val text = String.format(
            Locale.ROOT,
            "encoder timing: samples=%d encode_ms=%s hold_ms=%s queue_ms=%s lock_ms=%s write_ms=%s " +
                "backlog_max=%d samples %.1f MB full_waits=%d",
            encode.count, encode.text(), hold.text(), queue.text(), lock.text(), write.text(),
            maxBacklog, maxBacklogBytes / BytesPerMb, fullWaits,
        )
        listOf(encode, hold, queue, lock, write).forEach(Spread::clear)
        maxBacklog = backlog
        maxBacklogBytes = backlogBytes
        fullWaits = 0
        return text
    }

    /** Durations in tenths of a millisecond up to [Buckets]; longer ones count in the last bucket. */
    private class Spread {
        private val buckets = IntArray(Buckets)
        var count = 0
            private set
        private var maxNanos = 0L

        fun add(nanos: Long) {
            buckets[(nanos / NanosPerBucket).toInt().coerceIn(0, Buckets - 1)]++
            count++
            maxNanos = maxOf(maxNanos, nanos)
        }

        fun clear() {
            buckets.fill(0)
            count = 0
            maxNanos = 0
        }

        /** p50/p95/max in milliseconds; "-" with nothing added. */
        fun text(): String {
            if (count == 0) return "-"
            return listOf(ms(quantile(Half)), ms(quantile(P95)), ms(maxNanos)).joinToString("/")
        }

        private fun quantile(q: Double): Long {
            var seen = 0
            buckets.forEachIndexed { i, n ->
                seen += n
                if (seen >= q * count) return i * NanosPerBucket
            }
            return maxNanos
        }

        private fun ms(nanos: Long) = String.format(Locale.ROOT, "%.1f", nanos / NanosPerMilli)
    }

    private companion object {
        const val NanosPerBucket = 100_000L
        const val Buckets = 20_000
        const val NanosPerMilli = 1_000_000.0
        const val BytesPerMb = 1_000_000.0
        const val Half = 0.5
        const val P95 = 0.95
    }
}
