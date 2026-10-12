package com.flappedear.betterdriver.camera

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.R
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoSink
import com.flappedear.betterdriver.recording.RecorderProtocol
import com.flappedear.betterdriver.recording.RecorderService
import com.flappedear.betterdriver.recording.RecordingChannelId
import com.flappedear.betterdriver.recording.createRecordingChannel
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import com.flappedear.betterdriver.video.VideoIndex
import java.io.File

private const val NotificationId = 2
private const val NanosPerMicro = 1_000L

/** How long to wait for the recorder to report its session before giving up. */
private const val SessionWaitMs = 15_000L

/** After a stop, how long the camera gets to finish its file before the service ends anyway. */
private const val FinishMs = 5_000L

/** The video mode in a start intent ([CameraService.EXTRA_VIDEO_SIZE], [CameraService.EXTRA_VIDEO_FPS]). */
private fun videoMode(intent: Intent): VideoMode? {
    val name = intent.getStringExtra(CameraService.EXTRA_VIDEO_SIZE)
    val size = VideoSize.entries.firstOrNull { it.name == name }
    val fps = intent.getIntExtra(CameraService.EXTRA_VIDEO_FPS, 0)
    return if (size != null && fps > 0) VideoMode(size, fps) else null
}

/**
 * Phone video (FBBD-21) in its own process, `:camera`, as a foreground service of type camera.
 * A camera crash, hang or kill ends only this process: the recorder keeps recording.
 *
 * It binds to [RecorderService] like the UI does, waits for the running session, then records into
 * that session's video directory and forwards every camera report to the recorder ([VideoSink]
 * calls as [RecorderProtocol.MSG_VIDEO]). It stops when told to, when the session ends or changes,
 * or when the recorder process goes away.
 */
class CameraService : LifecycleService() {

    private val main = Handler(Looper.getMainLooper())
    private var recorder: Messenger? = null
    private var bound = false
    private var camera: PhoneCamera? = null
    private var sessionId: String? = null
    private var ending = false

    /** The mode Arek chose for the phone camera (FBBD-88), from the start intent. */
    private var mode: VideoMode = requireNotNull(EquipmentModel.PHONE_CAMERA.defaultVideo)

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val isState = msg.what == RecorderProtocol.MSG_STATE
            if (isState) onState(msg.data)
            isState
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recorder = Messenger(binder).also { send(it, Message.obtain(null, RecorderProtocol.MSG_REGISTER)) }
        }

        /** The recorder process died: its session is over, so video ends too. */
        override fun onServiceDisconnected(name: ComponentName) {
            recorder = null
            end()
        }
    }

    private val giveUp = Runnable { if (camera == null) end() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        DebugLog.i(Tag, "command ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> if (goForeground()) {
                videoMode(intent)?.let { mode = it }
                if (!bound) {
                    bound = bindService(Intent(this, RecorderService::class.java), connection, Context.BIND_AUTO_CREATE)
                }
                main.postDelayed(giveUp, SessionWaitMs)
            }
            else -> end()
        }
        // Not restarted by the system: a restarted camera service cannot know which session it belongs to.
        return START_NOT_STICKY
    }

    private fun audioGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("TooGenericExceptionCaught") // No camera permission, or not allowed to start from the background.
    private fun goForeground(): Boolean = try {
        createRecordingChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                if (audioGranted()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationId, notification(), type)
        true
    } catch (e: RuntimeException) {
        DebugLog.w(Tag, "could not start phone video", e)
        stopSelf()
        false
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RecordingChannelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.video_title))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun onState(data: Bundle) {
        val id = RecorderProtocol.fromBundle(data)?.sessionId
        val current = sessionId
        when {
            ending -> Unit
            current != null && id != current -> end() // The session ended or a new one began.
            current == null && id != null && data.containsKey(RecorderProtocol.KEY_START_NANOS) ->
                begin(id, data.getLong(RecorderProtocol.KEY_START_NANOS))
        }
    }

    private fun begin(id: String, startNanos: Long) {
        sessionId = id
        main.removeCallbacks(giveUp)
        val index = VideoIndex(File(sessionsDir(this), id))
        camera = PhoneCamera(this, this, index, audioGranted(), ladderFrom(mode))
            .also { it.start(RemoteSink(id, startNanos)) }
    }

    /** Stops video, gives the camera a moment to finish its file, then ends the service. */
    private fun end() {
        if (ending) return
        ending = true
        main.removeCallbacks(giveUp)
        val cam = camera
        if (cam == null) {
            finish()
        } else {
            cam.stop()
            main.postDelayed(::finish, FinishMs)
        }
    }

    private fun finish() {
        recorder?.let { send(it, Message.obtain(null, RecorderProtocol.MSG_UNREGISTER)) }
        if (bound) unbindService(connection)
        bound = false
        recorder = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        camera?.stop()
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    private fun send(to: Messenger, msg: Message) {
        msg.replyTo = incoming
        try {
            to.send(msg)
        } catch (_: RemoteException) {
            // The recorder process is gone; onServiceDisconnected follows.
        }
    }

    /** Forwards camera reports to the recorder, stamped on the session clock. */
    private inner class RemoteSink(private val session: String, private val startNanos: Long) : VideoSink {
        override fun nowMicros() = (System.nanoTime() - startNanos) / NanosPerMicro

        override fun started(file: String, startMicros: Long) =
            report(RecorderProtocol.VIDEO_STARTED, file, startMicros)

        override fun synced(file: String, atMicros: Long, mediaMicros: Long) =
            report(RecorderProtocol.VIDEO_SYNCED, file, atMicros) {
                putLong(RecorderProtocol.VIDEO_MEDIA_US, mediaMicros)
            }

        override fun stopped(file: String, atMicros: Long, reason: String) =
            report(RecorderProtocol.VIDEO_STOPPED, file, atMicros) { putString(RecorderProtocol.VIDEO_TEXT, reason) }

        override fun status(text: String, ok: Boolean) = report(RecorderProtocol.VIDEO_STATUS, "", nowMicros()) {
            putString(RecorderProtocol.VIDEO_TEXT, text)
            putBoolean(RecorderProtocol.VIDEO_OK, ok)
        }

        private fun report(kind: Int, file: String, atMicros: Long, extra: Bundle.() -> Unit = {}) {
            val to = recorder ?: return
            val data = Bundle().apply {
                putString(RecorderProtocol.VIDEO_SESSION, session)
                putString(RecorderProtocol.VIDEO_CAMERA, CameraIds.PHONE)
                putInt(RecorderProtocol.VIDEO_KIND, kind)
                putString(RecorderProtocol.VIDEO_FILE, file)
                putLong(RecorderProtocol.VIDEO_AT_US, atMicros)
                extra()
            }
            send(to, Message.obtain(null, RecorderProtocol.MSG_VIDEO).apply { this.data = data })
        }
    }

    companion object {
        const val ACTION_START = "com.flappedear.betterdriver.camera.START"
        const val ACTION_STOP = "com.flappedear.betterdriver.camera.STOP"

        /** The chosen video mode: [VideoSize] name and frames per second. */
        const val EXTRA_VIDEO_SIZE = "com.flappedear.betterdriver.camera.VIDEO_SIZE"
        const val EXTRA_VIDEO_FPS = "com.flappedear.betterdriver.camera.VIDEO_FPS"
        private const val Tag = "CameraService"
    }
}
