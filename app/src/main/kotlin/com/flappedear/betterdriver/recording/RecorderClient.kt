package com.flappedear.betterdriver.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.camera.CameraService
import com.flappedear.betterdriver.coach.CoachStatus
import com.flappedear.betterdriver.gopro.GoPros
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.timing.live.LiveTimingState

/** What the UI knows about the recorder process. */
data class RecorderState(
    /** Bound to the recorder process. */
    val connected: Boolean = false,
    /** The running recording, or null when nothing is recording. */
    val live: LiveSnapshot? = null,
    /** Start was asked for and the recorder has not answered yet. */
    val starting: Boolean = false,
    /** Why the last recording failed to start or ended on its own. */
    val error: String? = null,
    /** Phone video's short status ("4K · 60", "Stopped: too hot"), or null when no camera reports. */
    val camera: StatusText? = null,
    /** The track or circuit the recording is at, once detected (FBBD-55). */
    val place: String? = null,
    /** [place] is a saved track; false when it is only a circuit from the built-in list (FBBD-79). */
    val placeSaved: Boolean = false,
    /** Live timing, when the recording is at a saved track with lines (FBBD-12). */
    val timing: LiveTimingState? = null,
    /** The coach line (FBBD-98): the last cue, or whether the coach is learning; null when it says nothing. */
    val coach: CoachStatus? = null,
) {
    val recording: Boolean get() = live != null && live.stoppedReason == null
}

/** A short status line and whether it is OK (green) or needs attention (amber). */
data class StatusText(val text: String, val ok: Boolean)

/**
 * The UI side of [RecorderService]. Starts and stops recordings and follows the live state. It only
 * talks to the other process, so nothing here can affect what is recorded (FBBD-69).
 * One per process, so its state survives activity re-creation (the track screen turns to landscape).
 */
class RecorderClient private constructor(private val context: Context) {

    var state by mutableStateOf(RecorderState())
        private set

    private var service: Messenger? = null
    private var bound = false

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val isState = msg.what == RecorderProtocol.MSG_STATE
            if (isState) onState(msg)
            isState
        },
    )

    private fun onState(msg: Message) {
        val live = RecorderProtocol.fromBundle(msg.data)
        val error = msg.data.getString(RecorderProtocol.KEY_ERROR)
        val answered = live != null || error != null
        state = RecorderState(
            connected = true,
            live = live,
            starting = state.starting && !answered,
            // A local error (the recorder died) stays until a recording runs or the recorder reports its own.
            error = error ?: state.error.takeIf { live == null },
            camera = msg.data.getString(RecorderProtocol.KEY_CAMERA_STATUS)?.let {
                StatusText(it, msg.data.getBoolean(RecorderProtocol.KEY_CAMERA_OK))
            },
            place = msg.data.getString(RecorderProtocol.KEY_PLACE),
            placeSaved = msg.data.getBoolean(RecorderProtocol.KEY_PLACE_SAVED),
            timing = RecorderProtocol.timingFrom(msg.data),
            coach = msg.data.getString(RecorderProtocol.KEY_COACH_TEXT)?.let {
                CoachStatus(msg.data.getString(RecorderProtocol.KEY_COACH_CORNER), it)
            },
        )
        val running = live?.takeIf { it.stoppedReason == null }
        val startNanos = if (msg.data.containsKey(RecorderProtocol.KEY_START_NANOS)) {
            msg.data.getLong(RecorderProtocol.KEY_START_NANOS)
        } else {
            null
        }
        GoPros.get(context).onRecorderState(running?.sessionId, running?.startUtcMillis ?: 0L, startNanos)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder).also { send(it, RecorderProtocol.MSG_REGISTER) }
        }

        /** The recorder process died. Its session is safe on disk and is recovered now. */
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            val error = if (state.recording) "The recorder stopped unexpectedly. The session is saved." else state.error
            state = RecorderState(connected = false, error = error)
            GoPros.get(context).onRecorderState(null, 0L, null)
            Sessions.get(context).recoverAndRefresh()
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, RecorderService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        service?.let { send(it, RecorderProtocol.MSG_UNREGISTER) }
        context.unbindService(connection)
        service = null
        bound = false
    }

    /**
     * [gnss] is the session's receiver; with none, nothing is recorded and the track screen says why.
     * With [phoneVideo], the camera process starts too and records in that mode once the session
     * is running (FBBD-21, FBBD-88). It is started from here, while the app is on screen, because Android only lets a
     * camera service start from the foreground.
     */
    fun start(
        car: String,
        track: String,
        gnss: GnssRef?,
        phoneVideo: VideoMode? = null,
        sensors: List<SensorRef> = emptyList(),
    ) {
        if (gnss == null) {
            // Neither the recorder nor the camera starts, so no camera notification waits for a session.
            state = state.copy(starting = false, error = RecorderService.NoReceiver)
            return
        }
        state = state.copy(starting = true, error = null)
        val intent = Intent(context, RecorderService::class.java)
            .setAction(RecorderProtocol.ACTION_START)
            .putExtra(RecorderProtocol.EXTRA_CAR, car)
            .putExtra(RecorderProtocol.EXTRA_TRACK, track)
            .putExtra(RecorderProtocol.EXTRA_RACEBOX_ADDRESS, gnss.address)
            .putExtra(RecorderProtocol.EXTRA_RACEBOX_NAME, gnss.name)
            .putExtra(RecorderProtocol.EXTRA_GNSS_DEMO, gnss.demo)
            .putExtra(RecorderProtocol.EXTRA_GNSS_TYPE, gnss.type.name)
            .putExtra(RecorderProtocol.EXTRA_SENSORS, sensors.map { it.encode() }.toTypedArray())
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            state = state.copy(starting = false, error = "Could not start recording: ${e.message}")
            return
        }
        if (phoneVideo != null) startCamera(phoneVideo)
    }

    /** A failure here only means no phone video; the recording is already running. */
    private fun startCamera(mode: VideoMode) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraService::class.java)
                    .setAction(CameraService.ACTION_START)
                    .putExtra(CameraService.EXTRA_VIDEO_SIZE, mode.size.name)
                    .putExtra(CameraService.EXTRA_VIDEO_FPS, mode.fps),
            )
        } catch (e: IllegalStateException) {
            android.util.Log.w("RecorderClient", "phone video not started", e)
        }
    }

    fun stop() {
        try {
            context.startService(Intent(context, CameraService::class.java).setAction(CameraService.ACTION_STOP))
        } catch (_: IllegalStateException) {
            // Not running, or not allowed: it stops on its own when the session ends.
        }
        try {
            context.startService(Intent(context, RecorderService::class.java).setAction(RecorderProtocol.ACTION_STOP))
        } catch (e: IllegalStateException) {
            state = state.copy(error = "Could not stop recording: ${e.message}")
        }
    }

    companion object {
        @Volatile
        private var instance: RecorderClient? = null

        fun get(context: Context): RecorderClient = instance ?: synchronized(this) {
            instance ?: RecorderClient(context.applicationContext).also { instance = it }
        }
    }

    private fun send(to: Messenger, what: Int) {
        try {
            to.send(Message.obtain(null, what).apply { replyTo = incoming })
        } catch (_: RemoteException) {
            // The recorder process is gone; onServiceDisconnected follows.
        }
    }
}
