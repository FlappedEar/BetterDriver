package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.VideoSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A camera answered Set Shutter [action] ([START] or [STOP]) at [sessionUs] on the session clock, or gave up then. */
data class GoProAck(val action: String, val ok: Boolean, val sessionUs: Long) {
    companion object {
        const val START = "start"
        const val STOP = "stop"
    }
}

/** The phone's wall clock and time zone, as setting a camera's clock needs them. */
interface PhoneClock {
    fun nowMillis(): Long

    /** The local date and time at [utcMillis] in the phone's time zone. */
    fun local(utcMillis: Long): CameraDateTime

    fun utcOffsetMinutes(utcMillis: Long): Int

    fun daylightSaving(utcMillis: Long): Boolean
}

/**
 * An Open GoPro camera (HERO9 Black and later, MAX2) that starts and stops recording with a
 * session over Open GoPro BLE (https://gopro.github.io/OpenGoPro/docs), on Android and the iPhone
 * alike. The commands used here are listed for all of them in the spec (references.json); only
 * the clock command differs, see [setClock].
 *
 * On [start] it connects, sets the camera's clock from the phone (so file times can be matched to
 * the session), waits until the camera is not busy, applies [video] if given (FBBD-88; a refused
 * setting leaves the camera's own mode and the recording goes ahead), sends Set Shutter on, and
 * notes when the camera acknowledged it on the session clock. While recording it sends a
 * keep-alive every 3 s and reconnects if the link drops; the camera keeps recording on its own
 * meanwhile. On [stop] it sends Set Shutter off. The files stay on the camera and are downloaded
 * after the session ([GoProRecording] notes the recording for that, through the start's save).
 *
 * Nothing here can affect data recording: it runs in [scope], never throws from [start] or
 * [stop], and reports problems through [state].
 */
@Suppress("LongParameterList", "TooManyFunctions") // Clock and timeouts are test hooks; one step per function.
class OpenGoProCamera(
    val id: String,
    private val link: GoProLink,
    private val scope: CoroutineScope,
    private val clock: PhoneClock,
    /** The resolution and frame rate to set before recording, or null to keep the camera's own. */
    private val video: GoProVideo? = null,
    private val connectTimeoutMillis: Long = 20_000,
    private val readyTimeoutMillis: Long = 10_000,
    /** Told when the camera acknowledged Set Shutter on or off, or failed to, for the drive's log (FBBD-82). */
    private val acks: (GoProAck) -> Unit = {},
) {
    private val _state = MutableStateFlow<VideoSourceState>(VideoSourceState.Idle)
    val state: StateFlow<VideoSourceState> = _state.asStateFlow()

    private val client = GoProClient(link, scope)
    private var job: Job? = null
    private var connected = false
    private var sessionClock: (() -> Long)? = null
    private var save: suspend (GoProRecording) -> Unit = {}
    private var recording: GoProRecording? = null

    /**
     * Starts recording with a session whose clock, in microseconds since it started, is
     * [sessionClock]. [save] keeps the recording note for the download; it may throw [IoProblem].
     */
    @Suppress("TooGenericExceptionCaught") // Any failure only ends this camera, never the session.
    fun start(sessionClock: () -> Long, save: suspend (GoProRecording) -> Unit) {
        if (job?.isActive == true) return
        this.sessionClock = sessionClock
        this.save = save
        recording = null
        _state.value = VideoSourceState.Starting
        job = scope.launch {
            try {
                startRecording(sessionClock)
                keepAlive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any failure (no camera, refused, missing permission) only ends this camera.
                if (recording == null) acks(GoProAck(GoProAck.START, ok = false, sessionClock()))
                _state.value = VideoSourceState.Failed(e.message ?: "GoPro did not start")
            }
        }
    }

    /** Stops recording; returns when the camera answered or gave up. */
    @Suppress("TooGenericExceptionCaught") // Any failure only ends this camera, never the session.
    suspend fun stop() {
        val running = job
        job = null
        running?.cancel()
        running?.join()
        val started = recording ?: run {
            // Stopped while starting: the shutter command may have gone out, so turn it off.
            if (connected) {
                withTimeoutOrNull(readyTimeoutMillis) { runCatching { client.send(OpenGoPro.shutter(false)) } }
            }
            if (_state.value !is VideoSourceState.Failed) _state.value = VideoSourceState.Idle
            disconnectQuietly()
            return
        }
        _state.value = VideoSourceState.Stopping
        val stopped = withTimeoutOrNull(connectTimeoutMillis) {
            try {
                ensureConnected()
                client.send(OpenGoPro.shutter(false))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        } == true
        val at = sessionClock?.invoke()
        if (at != null) acks(GoProAck(GoProAck.STOP, stopped, at))
        if (stopped && at != null) {
            keep(started.copy(stopAckSessionUs = at, stopAckUtcMillis = clock.nowMillis()))
            _state.value = VideoSourceState.Idle
        } else {
            _state.value = VideoSourceState.Failed("GoPro did not stop; stop it by hand")
        }
        disconnectQuietly()
    }

    /** Stops listening to the link; the camera is not used again. */
    fun close() = client.close()

    private suspend fun startRecording(sessionClock: () -> Long) {
        withTimeoutOrNull(connectTimeoutMillis) { link.connect() } ?: throw GoProException("GoPro not found")
        connected = true
        val utcOffsetMinutes = setClock()
        waitUntilReady()
        val mode = applyVideo()
        client.send(OpenGoPro.shutter(true))
        val started = GoProRecording(
            sourceId = id,
            startAckSessionUs = sessionClock(),
            startAckUtcMillis = clock.nowMillis(),
            cameraUtcOffsetMinutes = utcOffsetMinutes,
            videoMode = mode,
        )
        recording = started
        acks(GoProAck(GoProAck.START, ok = true, started.startAckSessionUs))
        _state.value = VideoSourceState.Recording(started.startAckSessionUs)
        keep(started)
    }

    /**
     * Sets the camera's clock to the phone's local time; null if the camera refuses. Set Local Date
     * Time (0x0F) carries the time zone, but the HERO9 and HERO10 lack it (references.json), so a
     * refusal falls back to Set Date Time (0x0D), the same local time without the zone. Either way
     * the camera's file times are the phone's local time at [clock]'s offset.
     */
    private suspend fun setClock(): Int? {
        val now = clock.nowMillis()
        val offsetMinutes = clock.utcOffsetMinutes(now)
        val local = clock.local(now)
        return when {
            accepted(OpenGoPro.setLocalDateTime(local, offsetMinutes, clock.daylightSaving(now))) -> offsetMinutes
            accepted(OpenGoPro.setDateTime(local)) -> offsetMinutes
            else -> null
        }
    }

    /**
     * Sets resolution, then frame rate, so the frame rate is checked against the new resolution.
     * Returns what the camera took: [GoProVideo.label] for both, the size alone when it refused
     * only the frame rate, or null when it refused the resolution and records with its own mode.
     */
    private suspend fun applyVideo(): String? {
        val v = video ?: return null
        val applied = when {
            !accepted(OpenGoPro.Setting.set(OpenGoPro.Setting.VIDEO_RESOLUTION, v.resolution)) -> null
            accepted(OpenGoPro.Setting.set(OpenGoPro.Setting.FRAMES_PER_SECOND, v.fps)) -> v.label
            else -> "${v.size} · camera's frame rate"
        }
        if (applied != null) {
            try {
                waitUntilReady()
            } catch (_: GoProException) {
                // The shutter that follows reports a camera that is still busy.
            }
        }
        return applied
    }

    private suspend fun accepted(request: OpenGoPro.Request): Boolean = try {
        client.send(request)
        true
    } catch (_: GoProException) {
        false
    }

    private suspend fun waitUntilReady() {
        val ready = withTimeoutOrNull(readyTimeoutMillis) {
            while (busy()) delay(ReadyPollMillis)
        }
        if (ready == null) throw GoProException("GoPro stayed busy")
    }

    private suspend fun busy(): Boolean {
        val response = client.send(OpenGoPro.statuses(OpenGoPro.Status.BUSY))
        return OpenGoPro.flag(OpenGoPro.parseStatuses(response.payload)[OpenGoPro.Status.BUSY])
    }

    /** Runs until cancelled by [stop]. A lost link is retried; the camera records on its own meanwhile. */
    private suspend fun keepAlive() {
        while (true) {
            delay(OpenGoPro.KeepAliveIntervalMillis)
            try {
                ensureConnected()
                client.send(OpenGoPro.keepAlive())
            } catch (_: IoProblem) {
                connected = false
            }
        }
    }

    private suspend fun ensureConnected() {
        if (!connected) {
            withTimeoutOrNull(connectTimeoutMillis) { link.connect() } ?: throw GoProException("GoPro not found")
            connected = true
        }
    }

    private suspend fun disconnectQuietly() {
        connected = false
        try {
            link.disconnect()
        } catch (_: IoProblem) {
            // Already gone.
        }
    }

    private suspend fun keep(r: GoProRecording) {
        recording = r
        try {
            save(r)
        } catch (_: IoProblem) {
            // The download can still find the files by time; nothing else depends on this note.
        }
    }

    private companion object {
        const val ReadyPollMillis = 250L
    }
}
