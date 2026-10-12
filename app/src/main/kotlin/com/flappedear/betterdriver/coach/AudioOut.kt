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

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(Attributes)
        .setOnAudioFocusChangeListener({ }, handler)
        .build()

    /** Asks for focus for phrase [id], which replaces any phrase before it. */
    fun begin(id: String) {
        synchronized(lock) { current = id }
        val result = audio?.requestAudioFocus(focus)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
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
     * the first audio left, in [SystemClock.elapsedRealtimeNanos]. Returns when play was asked for, in
     * [SystemClock.uptimeMillis], or null if no track could be made.
     */
    fun play(pcm: Pcm, id: String, speaker: Boolean = false, onStart: (Long) -> Unit = {}): Long? {
        val t = build(pcm) ?: return null
        if (speaker) device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.let(t::setPreferredDevice)
        t.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) = finished(id, track)

                override fun onPeriodicNotification(track: AudioTrack) {
                    track.positionNotificationPeriod = 0
                    onStart(SystemClock.elapsedRealtimeNanos())
                }
            },
            handler,
        )
        t.notificationMarkerPosition = pcm.samples.size
        t.positionNotificationPeriod = pcm.frames(StartCheckMillis).coerceAtLeast(1)
        val old = synchronized(lock) { track.also { track = t } }
        old?.let(::release)
        begin(id)
        val at = SystemClock.uptimeMillis()
        return try {
            t.play()
            at
        } catch (e: IllegalStateException) {
            DebugLog.w(Tag, "clip not played", e)
            finished(id, t)
            null
        }
    }

    /** Stops the clip playing, if any, for a phrase said another way. */
    fun stopClip() {
        val old = synchronized(lock) { track.also { track = null } }
        old?.let(::release)
    }

    /** The Bluetooth or wired headset connected, by name, or null if sound goes to the phone. */
    fun headset(): String? = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { it.type in HeadsetTypes }?.productName?.toString()

    fun shutdown() {
        stopClip()
        synchronized(lock) { current = null }
        audio?.abandonAudioFocusRequest(focus)
    }

    private fun device(type: Int): AudioDeviceInfo? =
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.type == type }

    private fun finished(id: String, t: AudioTrack) {
        val mine = synchronized(lock) { (track === t).also { if (it) track = null } }
        if (mine) release(t)
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

    private fun release(t: AudioTrack) {
        try {
            t.stop()
        } catch (_: IllegalStateException) {
            // Never started or already stopped.
        }
        t.release()
    }

    companion object {
        /** Navigation guidance speech: ducks music, and the system routes it like a sat-nav's voice. */
        val Attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private const val Tag = "AudioOut"

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
