package com.flappedear.betterdriver.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.log.DebugLog

/**
 * Where the coach's voice goes (FBBD-47): played as navigation guidance speech, to whatever output is
 * active, so a Bluetooth helmet headset or the car gets it, else the phone speaker. Before each phrase
 * it asks for transient audio focus that lets music duck under it, and gives the focus back when the
 * phrase ends. If the focus is refused (a phone call, another app holding it) the phrase still plays,
 * and the refusal is logged. Also plays pre-rendered clips (FBBD-48) and the delay test's click
 * (FBBD-49) on the same path. Track callbacks run on [handler]'s thread.
 */
internal class AudioOut(context: Context, private val handler: Handler) {
    private val audio: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()

    /** The phrase playing or queued; the focus is held until it ends. Guarded by [lock]. */
    private var current: String? = null

    /** The clip track playing, released when it ends or another replaces it. Guarded by [lock]. */
    private var track: AudioTrack? = null

    /** Told how the playing clip ended: "done", or "stopped" when cut off. Guarded by [lock]. */
    private var trackEnd: ((String) -> Unit)? = null

    /** Whether the last phrase got audio focus (FBBD-47). */
    @Volatile
    var focusGranted = true
        private set

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(Attributes)
        .setOnAudioFocusChangeListener({ }, handler)
        .build()

    /** Asks for focus for phrase [id], which replaces any phrase before it. */
    fun begin(id: String) {
        synchronized(lock) { current = id }
        val result = audio?.requestAudioFocus(focus)
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusGranted) {
            DebugLog.w(Tag, "audio focus refused ($result) for $id; playing anyway")
        }
    }

    /** Phrase [id] ended, was stopped or failed: the focus goes back unless a newer phrase holds it. */
    fun end(id: String) {
        val last = synchronized(lock) { (current == id).also { if (it) current = null } }
        if (last) audio?.abandonAudioFocusRequest(focus)
    }

    /**
     * Plays [pcm] at once as phrase [id], stopping any clip still playing. [speaker] sends it to the phone
     * speaker even with a headset connected, for the delay test's baseline. [onStart] gets the time
     * the first audio left, in [SystemClock.elapsedRealtimeNanos]; [onEnd] how it ended: "done",
     * "stopped" when another sound cut it off, or "error". Returns when play was asked for, in
     * [SystemClock.uptimeMillis], or null if no track could be made or sent to the speaker.
     */
    fun play(
        pcm: Pcm,
        id: String,
        speaker: Boolean = false,
        onEnd: (String) -> Unit = {},
        onStart: (Long) -> Unit = {},
    ): Long? {
        val t = build(pcm)?.takeIf { !speaker || toSpeaker(it, audio, id) } ?: return null
        t.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) = finished(id, track, Done)

                override fun onPeriodicNotification(track: AudioTrack) {
                    track.positionNotificationPeriod = 0
                    onStart(SystemClock.elapsedRealtimeNanos())
                }
            },
            handler,
        )
        t.notificationMarkerPosition = pcm.samples.size
        t.positionNotificationPeriod = pcm.frames(StartCheckMillis).coerceAtLeast(1)
        val old = synchronized(lock) {
            (track to trackEnd).also {
                track = t
                trackEnd = onEnd
            }
        }
        cutOff(old)
        begin(id)
        val at = SystemClock.uptimeMillis()
        return try {
            t.play()
            at
        } catch (e: IllegalStateException) {
            DebugLog.w(Tag, "clip not played", e)
            finished(id, t, Failed)
            null
        }
    }

    /** Stops the clip playing, if any, for a phrase said another way. */
    fun stopClip() {
        val old = synchronized(lock) {
            (track to trackEnd).also {
                track = null
                trackEnd = null
            }
        }
        cutOff(old)
    }

    /** The type of output the sound goes to (FBBD-47): the headset's, else "speaker". */
    fun output(): String {
        val headset = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.type in HeadsetTypes }
        return when (headset?.type) {
            null -> "speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            else -> "wired"
        }
    }

    /** The Bluetooth or wired headset connected, by name, or null if sound goes to the phone. */
    fun headset(): String? = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { it.type in HeadsetTypes }?.productName?.toString()

    fun shutdown() {
        stopClip()
        synchronized(lock) { current = null }
        audio?.abandonAudioFocusRequest(focus)
    }

    private fun finished(id: String, t: AudioTrack, how: String) {
        val mine = synchronized(lock) {
            if (track === t) {
                true to trackEnd.also {
                    track = null
                    trackEnd = null
                }
            } else {
                false to null
            }
        }
        if (mine.first) {
            release(t)
            mine.second?.invoke(how)
        }
        end(id)
    }

    private fun build(pcm: Pcm): AudioTrack? {
        if (pcm.samples.isEmpty()) return null
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(Attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(pcm.samples.size * 2)
                .build()
                .also { it.write(pcm.samples, 0, pcm.samples.size) }
        } catch (e: UnsupportedOperationException) {
            DebugLog.w(Tag, "no audio track", e)
            null
        } catch (e: IllegalArgumentException) {
            DebugLog.w(Tag, "no audio track", e)
            null
        }
    }

    companion object {
        /** Navigation guidance speech: ducks music, and the system routes it like a sat-nav's voice. */
        val Attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private const val Tag = "AudioOut"
        private const val Done = "done"
        private const val Failed = "error"

        /** How much of a clip plays before its start is logged; a few milliseconds. */
        private const val StartCheckMillis = 5

        private val HeadsetTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}

/**
 * Sends [t] to the phone speaker; if that is refused it is released: a baseline click that would
 * play on the headset is no click.
 */
private fun toSpeaker(t: AudioTrack, audio: AudioManager?, id: String): Boolean {
    val speaker = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    val routed = speaker?.let(t::setPreferredDevice) == true
    if (!routed) {
        DebugLog.w("AudioOut", "clip $id not routed to the speaker")
        t.release()
    }
    return routed
}

/** Stops and frees [t]. */
private fun release(t: AudioTrack) {
    try {
        t.stop()
    } catch (_: IllegalStateException) {
        // Never started or already stopped.
    }
    t.release()
}

/** Frees the clip track another sound replaced, telling whoever played it that it was cut off. */
private fun cutOff(old: Pair<AudioTrack?, ((String) -> Unit)?>) {
    val (t, onEnd) = old
    if (t != null) {
        release(t)
        onEnd?.invoke("stopped")
    }
}
