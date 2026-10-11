package com.flappedear.betterdriver.coach

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.track.TrackDetection

/**
 * The recorder's side of the coach (FBBD-98): binds to [CoachService] for one recording and forwards
 * the session, the detected track and every fix to it. Runs as a recorder listener, so it only ever
 * reads what the recorder already has; a send to a coach that is gone is dropped. If the coach process
 * dies, Android restarts it (the binding asks for that) and it is told the session and track again.
 *
 * [reportTo] is the recorder's messenger, where the coach sends events and its line.
 */
internal class CoachLink(
    private val context: Context,
    private val sessionId: String,
    private val startNanos: Long,
    private val timing: SessionTiming,
    private val reportTo: Messenger,
) : RecorderListener {

    @Volatile
    private var coach: Messenger? = null

    /** The coach that was last told the track. Listener thread only. */
    private var trackSentTo: Messenger? = null

    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val m = Messenger(binder)
            send(m, CoachProtocol.MSG_SESSION) {
                putString(CoachProtocol.SESSION, sessionId)
                putLong(CoachProtocol.START_NANOS, startNanos)
            }
            coach = m
        }

        override fun onServiceDisconnected(name: ComponentName) {
            coach = null
        }
    }

    /** Main thread. */
    @Suppress("TooGenericExceptionCaught") // Not allowed to bind: no coach, the recording goes on.
    fun bind() {
        if (bound) return
        bound = try {
            context.bindService(Intent(context, CoachService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            Log.w(Tag, "coach not started", e)
            false
        }
    }

    /** Main thread. */
    fun unbind() {
        if (bound) context.unbindService(connection)
        bound = false
        coach = null
    }

    override fun onSamples(set: SampleSet) {
        val m = coach ?: return
        // The track is detected at the first position; until the coach knows it there is nothing to coach.
        if (trackSentTo !== m) sendTrack(m)
        val fix = set.toCoachFix()
        if (fix != null && trackSentTo === m) {
            send(m, CoachProtocol.MSG_FIX) {
                putLong(CoachProtocol.T_US, fix.tMicros)
                putDouble(CoachProtocol.LAT, fix.lat)
                putDouble(CoachProtocol.LON, fix.lon)
                putDouble(CoachProtocol.SPEED, fix.speedKmh ?: Double.NaN)
            }
        }
    }

    private fun sendTrack(m: Messenger) {
        val found = timing.detection ?: return
        send(m, CoachProtocol.MSG_TRACK) {
            (found as? TrackDetection.Saved)?.let { putString(CoachProtocol.TRACK_ID, it.track.id) }
        }
        trackSentTo = m
    }

    private fun send(to: Messenger, what: Int, fill: Bundle.() -> Unit) {
        try {
            to.send(Message.obtain(null, what).apply { data = Bundle().apply(fill); replyTo = reportTo })
        } catch (_: RemoteException) {
            // The coach process is gone; Android restarts it and onServiceConnected follows.
        }
    }

    private companion object {
        const val Tag = "CoachLink"
    }
}
