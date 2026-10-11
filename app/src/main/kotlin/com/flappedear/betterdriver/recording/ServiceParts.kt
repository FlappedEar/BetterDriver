package com.flappedear.betterdriver.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.R
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachProtocol
import com.flappedear.betterdriver.coach.CoachStatus
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.RecordingVideoSink

private val CoachEventKinds = setOf(CoachEvents.CUE, CoachEvents.RESULT, CoachEvents.PROBLEM)
private const val MaxCoachEventChars = 1_000

/** The low-importance channel for the recorder and camera notifications. */
internal const val RecordingChannelId = "recording"
private const val TickMs = 250L

/** Longer than any outing; the lock is released when the recording stops. */
private const val WakeLockTimeoutMs = 8 * 60 * 60 * 1_000L

internal fun createRecordingChannel(context: Context) {
    val channel = NotificationChannel(
        RecordingChannelId,
        context.getString(R.string.recording_channel),
        NotificationManager.IMPORTANCE_LOW,
    )
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/**
 * The ongoing notification Android requires for a foreground service: a title, a running clock and,
 * when known, track and car on a second line (UI-21 option B).
 */
internal fun recordingNotification(context: Context, startMillis: Long, detail: String = ""): Notification {
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, RecordingChannelId)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(context.getString(R.string.recording_title))
        .apply { if (detail.isNotBlank()) setContentText(detail) }
        .setWhen(startMillis)
        .setUsesChronometer(true)
        .setOngoing(true)
        .setContentIntent(open)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

/**
 * Keeps the CPU running while recording. The UI keeps the screen on, but if the UI dies the phone
 * may go to sleep, and the recorder must keep going.
 */
internal class CpuLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterDriver:recorder")
        .apply { setReferenceCounted(false) }

    fun acquire() = lock.acquire(WakeLockTimeoutMs)

    fun release() {
        if (lock.isHeld) lock.release()
    }
}

/**
 * The UI processes bound to the service, and the camera and coach processes that report into it.
 * Each registered client gets [state] every [TickMs] while any client is registered. Main thread only.
 */
internal class LiveClients(
    private val state: () -> Bundle,
    private val onVideo: (Bundle) -> Unit = {},
    private val onCoach: (Int, Bundle) -> Unit = { _, _ -> },
) {
    private val main = Handler(Looper.getMainLooper())
    private val clients = mutableListOf<Messenger>()
    private val ticker = object : Runnable {
        override fun run() {
            broadcast()
            if (clients.isNotEmpty()) main.postDelayed(this, TickMs)
        }
    }

    val messenger = Messenger(Handler(Looper.getMainLooper(), ::onMessage))

    private fun onMessage(msg: Message): Boolean {
        val client = msg.replyTo
        return when {
            msg.what == RecorderProtocol.MSG_VIDEO -> {
                onVideo(msg.data)
                true
            }
            msg.what == RecorderProtocol.MSG_COACH_EVENT || msg.what == RecorderProtocol.MSG_COACH_STATUS -> {
                onCoach(msg.what, msg.data)
                true
            }
            client == null -> false
            msg.what == RecorderProtocol.MSG_REGISTER -> {
                clients += client
                main.removeCallbacks(ticker)
                ticker.run()
                true
            }
            msg.what == RecorderProtocol.MSG_UNREGISTER -> {
                clients -= client
                true
            }
            else -> false
        }
    }

    fun broadcast() {
        val bundle = state()
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.send(Message.obtain(null, RecorderProtocol.MSG_STATE).apply { data = bundle })
            } catch (_: RemoteException) {
                iterator.remove() // That client's process is gone.
            }
        }
    }

    fun close() = main.removeCallbacks(ticker)
}

/**
 * Logs a camera report ([RecorderProtocol.MSG_VIDEO]) as a video event at the session time the
 * camera stamped, so nothing about the camera can stop or delay the recording. Reports for another
 * session are dropped. Status goes to [onStatus] for the status strip.
 */
internal fun logVideoReport(recording: Recording, data: Bundle, onStatus: (String, Boolean) -> Unit) {
    val camera = data.getString(RecorderProtocol.VIDEO_CAMERA)
    if (camera == null || data.getString(RecorderProtocol.VIDEO_SESSION) != recording.id) return
    val file = data.getString(RecorderProtocol.VIDEO_FILE).orEmpty()
    val at = data.getLong(RecorderProtocol.VIDEO_AT_US)
    val text = data.getString(RecorderProtocol.VIDEO_TEXT).orEmpty()
    val sink = RecordingVideoSink(recording, camera) { _, status, ok -> onStatus(status, ok) }
    when (data.getInt(RecorderProtocol.VIDEO_KIND)) {
        RecorderProtocol.VIDEO_STARTED -> sink.started(file, at)
        RecorderProtocol.VIDEO_SYNCED -> sink.synced(file, at, data.getLong(RecorderProtocol.VIDEO_MEDIA_US))
        RecorderProtocol.VIDEO_STOPPED -> sink.stopped(file, at, text)
        RecorderProtocol.VIDEO_STATUS -> sink.status(text, data.getBoolean(RecorderProtocol.VIDEO_OK))
    }
}

/**
 * Logs a coach report (FBBD-98) at the session time the coach stamped. Only coach event kinds, and
 * only for the running session, so nothing the coach sends can stop the recording. The coach line
 * goes to [onStatus].
 */
internal fun logCoachReport(recording: Recording, what: Int, data: Bundle, onStatus: (CoachStatus) -> Unit) {
    if (data.getString(CoachProtocol.SESSION) != recording.id) return
    when (what) {
        RecorderProtocol.MSG_COACH_EVENT -> {
            val kind = data.getString(CoachProtocol.EVENT_KIND).orEmpty()
            val text = data.getString(CoachProtocol.EVENT_TEXT).orEmpty().take(MaxCoachEventChars)
            if (kind in CoachEventKinds) recording.eventAt(data.getLong(CoachProtocol.T_US), kind, text)
        }
        RecorderProtocol.MSG_COACH_STATUS -> onStatus(
            CoachStatus(data.getString(CoachProtocol.STATUS_CORNER), data.getString(CoachProtocol.STATUS_TEXT)),
        )
    }
}
