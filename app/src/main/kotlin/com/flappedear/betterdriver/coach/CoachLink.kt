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
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.live.LiveFixAge
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.track.TrackDetection

/**
 * The recorder's side of the coach (FBBD-98): binds to [CoachService] for one recording and forwards
 * the session, the detected track and every fix to it. Runs as a recorder listener, so it only ever
 * reads what the recorder already has; a send to a coach that is gone is dropped. If the coach process
 * dies, Android restarts it (the binding asks for that) and it is told the session and track again.
 *
 * [reportTo] is the recorder's messenger, where the coach sends events and its line. [fixAge] measures
 * how old the receiver's fixes are when they arrive (FBBD-229); each fix is sent with it.
 */
internal class CoachLink(
    private val context: Context,
    private val sessionId: String,
    private val startNanos: Long,
    private val timing: SessionTiming,
    private val reportTo: Messenger,
    private val fixAge: LiveFixAge? = null,
) : RecorderListener {

    @Volatile
    private var coach: Messenger? = null

    /** The coach that was last told the track. Listener thread only. */
    private var trackSentTo: Messenger? = null

    /**
     * Whether the session is at a saved track, the only place the coach has anything to say. Elsewhere
     * (a road drive, a circuit with no saved track) fixes are not sent at all. Listener thread only.
     */
    private var atSavedTrack = false

    /** The session's channels so far, to find the pedal by (FBBD-307). Listener thread only. */
    private val channels = mutableMapOf<Int, ChannelDef>()

    /** Reads each fix with the last pedal value. Listener thread only. */
    private var reader = CoachFixReader(null)

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
            DebugLog.w(Tag, "coach not started", e)
            false
        }
    }

    /** Main thread. */
    fun unbind() {
        if (bound) context.unbindService(connection)
        bound = false
        coach = null
    }

    override fun onChannel(def: ChannelDef) {
        channels[def.id] = def
        // An OBD adapter that connects later brings its pedal channel then.
        val pedal = pedalChannelId(channels.values)
        val steering = steeringChannelId(channels.values)
        if (pedal != reader.pedal || steering != reader.steering) reader = CoachFixReader(pedal, steering)
    }

    override fun onSamples(set: SampleSet) {
        fixAge?.add(set)
        // Read before the coach is known, so the pedal value is there for the first fix.
        val fix = reader.read(set)
        val m = coach ?: return
        // The track is detected at the first position; until the coach knows it there is nothing to coach.
        if (trackSentTo !== m) sendTrack(m)
        if (fix != null && trackSentTo === m && atSavedTrack) {
            send(m, CoachProtocol.MSG_FIX) {
                putLong(CoachProtocol.T_US, fix.tMicros)
                putDouble(CoachProtocol.LAT, fix.lat)
                putDouble(CoachProtocol.LON, fix.lon)
                putDouble(CoachProtocol.SPEED, fix.speedKmh ?: Double.NaN)
                putLong(CoachProtocol.FIX_AGE_US, fixAge?.micros ?: 0L)
                putDouble(CoachProtocol.THROTTLE, fix.throttlePct ?: Double.NaN)
                putDouble(CoachProtocol.STEERING, fix.steerDeg ?: Double.NaN)
            }
        }
    }

    private fun sendTrack(m: Messenger) {
        val found = timing.detection ?: return
        send(m, CoachProtocol.MSG_TRACK) {
            (found as? TrackDetection.Saved)?.let { putString(CoachProtocol.TRACK_ID, it.track.id) }
        }
        atSavedTrack = found is TrackDetection.Saved
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
