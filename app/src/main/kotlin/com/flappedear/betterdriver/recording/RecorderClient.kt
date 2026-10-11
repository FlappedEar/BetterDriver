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
import com.flappedear.betterdriver.recorder.live.LiveSnapshot

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
) {
    val recording: Boolean get() = live != null && live.stoppedReason == null
}

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
        )
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

    /** [raceBox] is the paired RaceBox; with none, the recording uses the fake GNSS source. */
    fun start(car: String, track: String, raceBox: RaceBoxRef?) {
        state = state.copy(starting = true, error = null)
        val intent = Intent(context, RecorderService::class.java)
            .setAction(RecorderProtocol.ACTION_START)
            .putExtra(RecorderProtocol.EXTRA_CAR, car)
            .putExtra(RecorderProtocol.EXTRA_TRACK, track)
            .putExtra(RecorderProtocol.EXTRA_RACEBOX_ADDRESS, raceBox?.address)
            .putExtra(RecorderProtocol.EXTRA_RACEBOX_NAME, raceBox?.name)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            state = state.copy(starting = false, error = "Could not start recording: ${e.message}")
        }
    }

    fun stop() {
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
