package com.flappedear.betterdriver.recording

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.flappedear.betterdriver.core.ble.RaceBoxChannels
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitDirectory
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackStore
import java.util.concurrent.Executors

private const val NotificationId = 1
private const val DestroyStopTimeoutMs = 3_000L

private const val KeyRaceBoxAddress = "racebox_address"
private const val KeyRaceBoxName = "racebox_name"
private const val KeyGnssDemo = "gnss_demo"

/** The receiver of the recording that was running when the recorder process was killed. */
private fun wantedGnss(wanted: SharedPreferences): GnssRef? = when {
    wanted.getBoolean(KeyGnssDemo, false) -> GnssRef.Demo
    else -> wanted.getString(KeyRaceBoxAddress, null)?.let {
        GnssRef(it, wanted.getString(KeyRaceBoxName, "").orEmpty())
    }
}

/** The receiver a start intent names: Demo GNSS, a RaceBox, or none. */
private fun gnssFrom(intent: Intent): GnssRef? = when {
    intent.getBooleanExtra(RecorderProtocol.EXTRA_GNSS_DEMO, false) -> GnssRef.Demo
    else -> intent.getStringExtra(RecorderProtocol.EXTRA_RACEBOX_ADDRESS)?.let {
        GnssRef(it, intent.getStringExtra(RecorderProtocol.EXTRA_RACEBOX_NAME).orEmpty())
    }
}

/** Track and car, the ones that are known (UI-21 option B). */
private fun notificationDetail(info: SessionInfo) =
    listOf(info.track, info.car).filter { it.isNotBlank() }.joinToString(" · ")

private const val TrackEvent = "track"

/** The session's record of where it was: "saved <id> rev <n> <name>", "circuit <id> <name>" or "unknown". */
private fun trackEventText(found: TrackDetection) = when (found) {
    is TrackDetection.Saved -> "saved ${found.track.id} rev ${found.track.revision} ${found.track.displayName}"
    is TrackDetection.Directory -> "circuit ${found.circuit.id} ${found.circuit.name}"
    TrackDetection.Unknown -> "unknown"
}

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

    private class Active(val recording: Recording, val tracker: LiveTracker, val timing: SessionTiming)

    private val main = Handler(Looper.getMainLooper())

    /** Starts and stops run here, off the main thread: both touch the disk and stop can wait for the writer. */
    private val control = Executors.newSingleThreadExecutor { Thread(it, "recorder-control") }
    private lateinit var store: SessionStore
    private lateinit var cpu: CpuLock

    /** Camera reports (FBBD-21) from the camera process are logged into the running recording. */
    private val clients = LiveClients(::state) { data ->
        active?.recording?.let { logVideoReport(it, data) { text, ok -> cameraStatus = text to ok } }
    }
    private val wanted by lazy { getSharedPreferences("recorder", Context.MODE_PRIVATE) }

    /** Read once, on the control thread; names circuits for tracks not saved yet (FBBD-79). */
    private val circuits: CircuitList by lazy { CircuitDirectory(circuitsDir(this)).load() }

    @Volatile
    private var active: Active? = null

    @Volatile
    private var lastError: String? = null

    /** The camera's last status for the status strip, for the running recording. Main thread. */
    private var cameraStatus: Pair<String, Boolean>? = null

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
                if (goForeground()) startRecording(car, track, gnssFrom(intent))
            }
            RecorderProtocol.ACTION_STOP -> stopRecording()
            // Restarted by the system after this process was killed (START_STICKY). If a recording was
            // running, the old session is recovered at the next app start and a new one carries on.
            null -> if (wanted.getBoolean(KeyWanted, false) && goForeground()) {
                val car = wanted.getString(KeyCar, "").orEmpty()
                startRecording(car, wanted.getString(KeyTrack, "").orEmpty(), wantedGnss(wanted))
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
    private fun startRecording(car: String, track: String, gnss: GnssRef?) = control.execute {
        if (active != null) return@execute
        if (gnss == null) {
            // Only reachable from an older app build or a stale restart; home needs a receiver to start.
            lastError = NoReceiver
            main.post {
                wanted.edit().putBoolean(KeyWanted, false).commit()
                endForeground()
            }
            return@execute
        }
        try {
            val sources = Sources.create(this, gnss)
            val info = SessionInfo(
                startUtcMillis = System.currentTimeMillis(),
                car = car,
                track = track,
                devices = sources.map { it.name },
                channels = GnssChannels.ALL,
            )
            val recording = Recorder(store).start(info)
            // G for the track screen comes from the RaceBox accelerometer; Demo GNSS has none (UI-38).
            val accel = if (gnss.demo) null else RaceBoxChannels.ACCEL_X.id to RaceBoxChannels.ACCEL_Y.id
            val tracker = LiveTracker(
                recording.id,
                info.startUtcMillis,
                recording::nowMicros,
                droppedSamples = { recording.droppedCount },
                accelIds = accel,
            )
            recording.listeners.add(tracker)
            // Picks the track at the first fix and times laps there (FBBD-55, FBBD-12). It runs on the
            // listener thread, so a timing failure is logged in the session and never stops it.
            val timing = SessionTiming({ TrackStore(tracksDir(this)).all() }, circuits) { found ->
                recording.event(TrackEvent, trackEventText(found))
            }
            recording.listeners.add(timing)
            // Covers stops the recorder makes itself (storage full, write error).
            recording.listeners.add(object : RecorderListener {
                override fun onStatus(status: RecorderStatus) {
                    if (status is RecorderStatus.Stopped) main.post { onEnded(recording, status.reason) }
                }
            })
            main.post { cameraStatus = null }
            active = Active(recording, tracker, timing)
            sources.forEach(recording::attach)
            main.post {
                cpu.acquire()
                wanted.edit().putBoolean(KeyWanted, true).putString(KeyCar, car).putString(KeyTrack, track)
                    .putString(KeyRaceBoxAddress, gnss.address).putString(KeyRaceBoxName, gnss.name)
                    .putBoolean(KeyGnssDemo, gnss.demo).commit()
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
        val a = active
        val state = a?.tracker?.snapshot()?.let(RecorderProtocol::toBundle) ?: Bundle()
        if (a != null) {
            state.putLong(RecorderProtocol.KEY_START_NANOS, a.recording.startNanos)
            cameraStatus?.let { (text, ok) ->
                state.putString(RecorderProtocol.KEY_CAMERA_STATUS, text)
                state.putBoolean(RecorderProtocol.KEY_CAMERA_OK, ok)
            }
        }
        a?.timing?.detection?.placeName?.let { state.putString(RecorderProtocol.KEY_PLACE, it) }
        a?.timing?.state?.let { RecorderProtocol.putTiming(state, it) }
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

    companion object {
        private const val UserStop = "user"
        private const val KeyWanted = "wanted"
        private const val KeyCar = "car"

        /** Shown on the track screen when a start has no GNSS receiver; nothing is recorded. */
        const val NoReceiver = "No GNSS receiver. Add one in Settings › Devices."
        private const val KeyTrack = "track"
    }
}
