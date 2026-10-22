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
import com.flappedear.betterdriver.recorder.log.CheckLog

private val CoachEventKinds =
    setOf(
        CoachEvents.CUE,
        CoachEvents.RESULT,
        CoachEvents.PROBLEM,
        CoachEvents.VOICE,
        CoachEvents.VOICE_END,
        CoachEvents.FOCUS,
        // When and why the coach went quiet and came back (FBBD-202, FBBD-254): the session and the
        // continuous log show which laps it took as cool-down, as the replay checks read them.
        CoachEvents.QUIET,
        CoachEvents.RESUMED,
    )
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
    private val onUi: (Bundle) -> Unit = {},
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
            msg.what == RecorderProtocol.MSG_UI_EVENT -> {
                onUi(msg.data)
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
 * How long the camera service waits, after a stop, for its last file to be finished before it ends
 * anyway (audit A13): the own encoder may take 10 s for each of its two encoders, 60 s for a file
 * handed over just before, then the last file's index.
 */
internal const val CameraFinishWaitMs = 90_000L

/** How long the stop's report waits for the camera to close its last file: longer than the camera gives it. */
internal const val VideoCloseWaitMs = CameraFinishWaitMs + 5_000L

/** The stop reason of a file that filled up and handed over to the next (PhoneCamera). */
private const val FullFile = "file_full"

/** Stands for the file a full one hands over to, until its start report arrives. */
private const val NextFile = "/next"

/**
 * Holds what must wait for a session's camera files to close (FBBD-247): the report made at the stop
 * ran 0.7 s before a 2.7K file was finished and counted its last seconds as missing video. It follows
 * the files the camera process reports open and closed, and runs a held action once none is open, or
 * after [waitMs] if the camera never says. [schedule] and [cancel] post and remove a delayed action.
 * Main thread.
 */
internal class AfterVideo(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val waitMs: Long = VideoCloseWaitMs,
) {
    private val open = HashMap<String, MutableSet<String>>()
    private val held = HashMap<String, Runnable>()

    /**
     * A camera report: [kind] ([RecorderProtocol.VIDEO_STARTED] and so on) of [file] in [session], with
     * a stop's [reason]. A file that stopped full hands over to a next one whose start report follows,
     * so it stays open as [NextFile] until that start; with no next file the wait runs out.
     */
    fun report(session: String, kind: Int, file: String, reason: String = "") {
        when (kind) {
            RecorderProtocol.VIDEO_STARTED -> open.getOrPut(session) { mutableSetOf() }.apply {
                remove(NextFile)
                add(file)
            }
            RecorderProtocol.VIDEO_STOPPED -> {
                val files = open[session] ?: return
                files -= file
                if (reason == FullFile) files += NextFile
                if (files.isEmpty()) held[session]?.let { cancel(it); it.run() }
            }
        }
    }

    /** Runs [then] once [session]'s camera files are closed; at once when none is open. */
    fun whenClosed(session: String, then: () -> Unit) {
        if (open[session].isNullOrEmpty()) {
            open.remove(session)
            then()
            return
        }
        val run = object : Runnable {
            override fun run() {
                if (held[session] !== this) return
                held.remove(session)
                open.remove(session)
                then()
            }
        }
        held[session] = run
        schedule(run, waitMs)
    }
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
        RecorderProtocol.VIDEO_STARTED -> sink.started(
            file,
            at,
            data.getInt(RecorderProtocol.VIDEO_FPS).takeIf { data.containsKey(RecorderProtocol.VIDEO_FPS) },
        )
        RecorderProtocol.VIDEO_SYNCED -> sink.synced(
            file,
            at,
            data.getLong(RecorderProtocol.VIDEO_MEDIA_US),
            data.getLong(RecorderProtocol.VIDEO_FRAME_US).takeIf { data.containsKey(RecorderProtocol.VIDEO_FRAME_US) },
        )
        RecorderProtocol.VIDEO_STOPPED -> sink.stopped(file, at, text)
        RecorderProtocol.VIDEO_STATUS -> sink.status(text, data.getBoolean(RecorderProtocol.VIDEO_OK))
        RecorderProtocol.VIDEO_THERMAL -> sink.thermal(
            at,
            data.getInt(RecorderProtocol.VIDEO_THERMAL_STATUS),
            data.getFloat(RecorderProtocol.VIDEO_HEADROOM, Float.NaN).takeUnless { it.isNaN() },
            data.getBoolean(RecorderProtocol.VIDEO_HEAT_IGNORED),
        )
    }
}

/**
 * Logs a coach report (FBBD-98) at the session time the coach stamped. Only coach event kinds, and
 * only for the running session, so nothing the coach sends can stop the recording. The coach line
 * goes to [onStatus].
 */
/** Records a UI report ([RecorderProtocol.MSG_UI_EVENT]) in [recording], when it is for it (FBBD-183). */
internal fun logUiReport(recording: Recording, data: Bundle) {
    val kind = data.getString(RecorderProtocol.UI_KIND).orEmpty()
    if (data.getString(RecorderProtocol.UI_SESSION) != recording.id || kind !in CheckLog.UiEventKinds) return
    val text = data.getString(RecorderProtocol.UI_TEXT).orEmpty().take(MaxCoachEventChars)
    recording.eventAt(data.getLong(RecorderProtocol.UI_T_US), kind, text)
}

internal fun logCoachReport(recording: Recording, what: Int, data: Bundle, onStatus: (CoachStatus) -> Unit) {
    if (data.getString(CoachProtocol.SESSION) != recording.id) return
    when (what) {
        RecorderProtocol.MSG_COACH_EVENT -> {
            val kind = data.getString(CoachProtocol.EVENT_KIND).orEmpty()
            val text = data.getString(CoachProtocol.EVENT_TEXT).orEmpty().take(MaxCoachEventChars)
            if (kind in CoachEventKinds) recording.eventAt(data.getLong(CoachProtocol.T_US), kind, text)
        }
        RecorderProtocol.MSG_COACH_STATUS -> onStatus(
            CoachStatus(
                data.getString(CoachProtocol.STATUS_CORNER),
                data.getString(CoachProtocol.STATUS_TEXT),
                data.getBoolean(CoachProtocol.STATUS_PRAISE),
            ),
        )
    }
}
