package com.flappedear.betterdriver.recording

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.store.SessionStore
import java.util.concurrent.Executors

private const val NotificationId = 1
private const val DestroyStopTimeoutMs = 3_000L

/** Track and car, the ones that are known (UI-21 option B). */
private fun notificationDetail(info: SessionInfo) =
    listOf(info.track, info.car).filter { it.isNotBlank() }.joinToString(" · ")

/** Shown on the track screen when a recording ends without the driver stopping it. */
private fun endedMessage(reason: String) = when (reason) {
    "storage_full" -> "Storage full. The session is saved."
    else -> "Recording stopped ($reason). The session is saved."
}

/**
 * Hosts the recorder in its own process (android:process=":recorder"), as a foreground service, so
 * a crash or kill of the UI, track screen, coach or analysis never stops a recording (FBBD-69).
 * This process runs only the recorder, its data sources and the session store.
 *
 * The UI starts and stops recordings with [RecorderProtocol.ACTION_START] and
 * [RecorderProtocol.ACTION_STOP], and binds to receive live data. Swiping the app away or a UI
 * crash leaves the recording running; opening the app again reconnects to it.
 */
class RecorderService : Service() {

    private class Active(val recording: Recording, val tracker: LiveTracker)

    private val main = Handler(Looper.getMainLooper())

    /** Starts and stops run here, off the main thread: both touch the disk and stop can wait for the writer. */
    private val control = Executors.newSingleThreadExecutor { Thread(it, "recorder-control") }
    private lateinit var store: SessionStore
    private lateinit var cpu: CpuLock
    private val clients = LiveClients(::state)
    private val wanted by lazy { getSharedPreferences("recorder", Context.MODE_PRIVATE) }

    @Volatile
    private var active: Active? = null

    @Volatile
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(sessionsDir(this))
        cpu = CpuLock(this)
        createRecordingChannel(this)
    }

    override fun onBind(intent: Intent): IBinder = clients.messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecorderProtocol.ACTION_START -> {
                lastError = null
                val car = intent.getStringExtra(RecorderProtocol.EXTRA_CAR).orEmpty()
                val track = intent.getStringExtra(RecorderProtocol.EXTRA_TRACK).orEmpty()
                val raceBox = intent.getStringExtra(RecorderProtocol.EXTRA_RACEBOX_ADDRESS)?.let {
                    RaceBoxRef(it, intent.getStringExtra(RecorderProtocol.EXTRA_RACEBOX_NAME).orEmpty())
                }
                if (goForeground()) startRecording(car, track, raceBox)
            }
            RecorderProtocol.ACTION_STOP -> stopRecording()
            // Restarted by the system after this process was killed (START_STICKY). If a recording was
            // running, the old session is recovered at the next app start and a new one carries on.
            null -> if (wanted.getBoolean(KeyWanted, false) && goForeground()) {
                val raceBox = wanted.getString(KeyRaceBoxAddress, null)?.let {
                    RaceBoxRef(it, wanted.getString(KeyRaceBoxName, "").orEmpty())
                }
                val car = wanted.getString(KeyCar, "").orEmpty()
                startRecording(car, wanted.getString(KeyTrack, "").orEmpty(), raceBox)
            } else {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    @Suppress("TooGenericExceptionCaught") // Not allowed to go foreground (Android 12+) or no location access (14+).
    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        val info = active?.recording?.info
        val notification = recordingNotification(
            this,
            info?.startUtcMillis ?: System.currentTimeMillis(),
            info?.let(::notificationDetail).orEmpty(),
        )
        ServiceCompat.startForeground(this, NotificationId, notification, type)
        true
    } catch (e: RuntimeException) {
        lastError = "Could not start recording: ${e.message}"
        stopSelf()
        false
    }

    @Suppress("TooGenericExceptionCaught") // Any startup failure is reported to the UI, never a crash.
    private fun startRecording(car: String, track: String, raceBox: RaceBoxRef?) = control.execute {
        if (active != null) return@execute
        try {
            val sources = Sources.create(this, raceBox)
            val info = SessionInfo(
                startUtcMillis = System.currentTimeMillis(),
                car = car,
                track = track,
                devices = sources.map { it.name },
                channels = GnssChannels.ALL,
            )
            val recording = Recorder(store).start(info)
            val tracker = LiveTracker(recording.id, info.startUtcMillis, recording::nowMicros) {
                recording.droppedCount
            }
            recording.listeners.add(tracker)
            // Covers stops the recorder makes itself (storage full, write error).
            recording.listeners.add(object : RecorderListener {
                override fun onStatus(status: RecorderStatus) {
                    if (status is RecorderStatus.Stopped) main.post { onEnded(recording, status.reason) }
                }
            })
            active = Active(recording, tracker)
            sources.forEach(recording::attach)
            main.post {
                cpu.acquire()
                wanted.edit().putBoolean(KeyWanted, true).putString(KeyCar, car).putString(KeyTrack, track)
                    .putString(KeyRaceBoxAddress, raceBox?.address).putString(KeyRaceBoxName, raceBox?.name).commit()
                getSystemService(NotificationManager::class.java)
                    .notify(NotificationId, recordingNotification(this, info.startUtcMillis, notificationDetail(info)))
            }
        } catch (e: Exception) {
            lastError = "Could not start recording: $e"
            main.post { endForeground() }
        }
    }

    private fun stopRecording() {
        // Cleared first, so a process death while stopping does not start a new session.
        wanted.edit().putBoolean(KeyWanted, false).commit()
        control.execute {
            val a = active
            if (a == null) {
                main.post { endForeground() }
            } else {
                a.recording.stop(UserStop)
                main.post { onEnded(a.recording, UserStop) }
            }
        }
    }

    /** Main thread. Runs once per recording, whichever of the user stop or the recorder's own stop comes first. */
    private fun onEnded(recording: Recording, reason: String) {
        if (active?.recording !== recording) return
        active = null
        if (reason != UserStop) lastError = endedMessage(reason)
        wanted.edit().putBoolean(KeyWanted, false).commit()
        clients.broadcast()
        endForeground()
    }

    private fun endForeground() {
        cpu.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The current recording's live values, or an empty bundle when idle; plus the last start error. */
    private fun state(): Bundle {
        val state = active?.tracker?.snapshot()?.let(RecorderProtocol::toBundle) ?: Bundle()
        lastError?.let { state.putString(RecorderProtocol.KEY_ERROR, it) }
        return state
    }

    override fun onDestroy() {
        clients.close()
        // Only reached with a recording running if the system destroys the service: finish it cleanly.
        active?.recording?.stop("service_destroyed", DestroyStopTimeoutMs)
        active = null
        cpu.release()
        control.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val UserStop = "user"
        const val KeyWanted = "wanted"
        const val KeyCar = "car"
        const val KeyTrack = "track"
        const val KeyRaceBoxAddress = "racebox_address"
        const val KeyRaceBoxName = "racebox_name"
    }
}
