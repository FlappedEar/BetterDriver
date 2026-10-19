package com.flappedear.betterdriver.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import java.nio.ByteBuffer

/** 16-bit PCM sound for [WideEncoder], with each read's time on the camera's clock. */
internal interface PcmSource {
    val sampleRate: Int
    val channels: Int

    fun start()

    /**
     * Fills [into] from its position with up to its remaining bytes, blocking until some arrive;
     * returns the bytes read and the time of the first, in nanoseconds; null once stopped or failed.
     */
    fun read(into: ByteBuffer): PcmRead?

    fun stop()
}

/** [bytes] of sound whose first sample was recorded at [nanos]. */
internal data class PcmRead(val bytes: Int, val nanos: Long)

/**
 * The phone's camcorder microphone, as MediaRecorder recorded it for 2.7K: 48 kHz mono. Each read is
 * timed by the frame position Android reports, on the monotonic clock: the camera stamps the frames
 * it draws into a video encoder on that clock, whatever its sensor timestamps are.
 */
internal class MicSource : PcmSource {
    override val sampleRate = MicRate
    override val channels = 1

    @Volatile private var record: AudioRecord? = null
    private var framesRead = 0L
    private val stamp = AudioTimestamp()

    @SuppressLint("MissingPermission") // Sound is only asked for with RECORD_AUDIO granted (CameraService).
    override fun start() {
        val min = AudioRecord.getMinBufferSize(MicRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, MicRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, MicRate / BuffersPerSecond * BytesPerSample) * BufferFactor,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("microphone not initialized")
        }
        record = r
        framesRead = 0
        r.startRecording()
    }

    override fun read(into: ByteBuffer): PcmRead? {
        val r = record ?: return null
        val n = r.read(into, into.remaining())
        return if (n <= 0) {
            null
        } else {
            val frames = n / BytesPerSample
            PcmRead(n, timeOf(r, framesRead, frames)).also { framesRead += frames }
        }
    }

    /** When frame [frame] was recorded: from Android's latest timestamp, else now less the [frames] just read. */
    private fun timeOf(r: AudioRecord, frame: Long, frames: Int): Long {
        return if (r.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            stamp.nanoTime + (frame - stamp.framePosition) * NanosPerSecond / MicRate
        } else {
            System.nanoTime() - frames.toLong() * NanosPerSecond / MicRate
        }
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        r.release()
    }

    private companion object {
        const val MicRate = 48_000
        const val BytesPerSample = 2
        const val BuffersPerSecond = 50
        const val BufferFactor = 4
        const val NanosPerSecond = 1_000_000_000L
    }
}
