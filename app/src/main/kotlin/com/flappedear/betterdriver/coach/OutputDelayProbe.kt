package com.flappedear.betterdriver.coach

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.log.DebugLog

/** The output's delay as measured (FBBD-49), the output's type ("bt_a2dp", "speaker", ...) and the headset's name. */
internal data class MeasuredDelay(val millis: Int, val output: String, val headset: String?)

/**
 * Measures the audio output's own delay (FBBD-49) with nothing for the driver to do: plays a moment of
 * silence on the coach voice's path (same audio attributes, so the same output: the helmet headset, the
 * car or the phone speaker) and reads Android's audio clock while it plays ([OutputDelay]). [lowLatency]
 * plays it on the low-latency path, as pre-rendered clips are (FBBD-48); off, as live text-to-speech
 * is. Blocks for
 * up to [MaxMillis]; call it off the main thread. Null when Android gives no audio clock for the
 * output. Each measurement goes in the continuous log.
 */
internal object OutputDelayProbe {
    fun measure(context: Context, lowLatency: Boolean = false): MeasuredDelay? {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val out = AudioOutputs(audio)
        val before = out.output()
        val track = build(lowLatency) ?: return null
        val readings = mutableListOf<Double>()
        try {
            track.play()
            val started = SystemClock.uptimeMillis()
            val ts = AudioTimestamp()
            while (SystemClock.uptimeMillis() - started < MaxMillis && readings.size < Readings) {
                Thread.sleep(StepMillis)
                // Early timestamps can come before the output (a Bluetooth link waking) has settled.
                if (SystemClock.uptimeMillis() - started >= SettleMillis) reading(track, ts)?.let(readings::add)
            }
        } catch (e: IllegalStateException) {
            DebugLog.w(Tag, "output delay not measured", e)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
                // Never started.
            }
            track.release()
        }
        // A headset connected or gone meanwhile: the readings may be of either output, so none count.
        val output = out.output()
        if (output != before) readings.clear()
        val millis = OutputDelay.of(readings)
        DebugLog.i(
            Tag,
            "output delay ${millis?.let { "$it ms" } ?: "not reported"} on $output" +
                (out.headset()?.let { " ($it)" }.orEmpty()) + ", ${readings.size} reading(s)",
        )
        return millis?.let { MeasuredDelay(it, output, out.headset()) }
    }

    /** One reading of the delay, or null while the output gives no timestamp or has not started. */
    private fun reading(track: AudioTrack, ts: AudioTimestamp): Double? {
        if (!track.getTimestamp(ts)) return null
        val head = track.playbackHeadPosition.toLong() and Uint32
        val now = System.nanoTime()
        return if (ts.framePosition > 0 && head > ts.framePosition) {
            OutputDelay.readingMillis(head, ts.framePosition, ts.nanoTime, now, SampleRate)
        } else {
            null
        }
    }

    private fun build(lowLatency: Boolean): AudioTrack? = try {
        val frames = SampleRate * SilenceMillis / MillisPerSecond
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        AudioTrack.Builder()
            .setAudioAttributes(AudioOut.Attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .setBufferSizeInBytes(frames * 2)
            .build()
            .also { it.write(ShortArray(frames), 0, frames) }
    } catch (e: UnsupportedOperationException) {
        DebugLog.w(Tag, "no audio track", e)
        null
    } catch (e: IllegalArgumentException) {
        DebugLog.w(Tag, "no audio track", e)
        null
    }

    private const val Tag = "OutputDelay"
    private const val SampleRate = 48_000
    private const val MillisPerSecond = 1_000
    private const val SilenceMillis = 1_500
    private const val MaxMillis = 1_200L
    private const val SettleMillis = 300L
    private const val StepMillis = 60L
    private const val Readings = 8
    private const val Uint32 = 0xFFFF_FFFFL
}
