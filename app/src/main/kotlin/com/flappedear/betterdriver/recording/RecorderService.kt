package com.flappedear.betterdriver.recording

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.flappedear.betterdriver.analysis.quality.logLines
import com.flappedear.betterdriver.coach.CoachLink
import com.flappedear.betterdriver.coach.CoachStatus
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.coachSettings
import com.flappedear.betterdriver.core.ble.RaceBoxChannels
import com.flappedear.betterdriver.core.ble.RaceBoxGatt
import com.flappedear.betterdriver.core.ble.nmea.RcChannels
import com.flappedear.betterdriver.core.ble.hr.HeartRateSource
import com.flappedear.betterdriver.core.ble.obd.ObdSource
import com.flappedear.betterdriver.debug.StartupLog
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.PhoneGnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.RecordingEnd
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.live.LiveFixAge
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.GnssSecondLog
import com.flappedear.betterdriver.recorder.log.HeartRateLog
import com.flappedear.betterdriver.recorder.log.LoggingListener
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitDirectory
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val NotificationId = 1

/** Event kinds the coach process may log (FBBD-98), and the longest text it may log. */
private const val DestroyStopTimeoutMs = 3_000L

private const val KeyRaceBoxAddress = "racebox_address"
private const val KeyRaceBoxName = "racebox_name"
private const val KeyGnssDemo = "gnss_demo"
private const val KeyGnssNmea = "gnss_nmea"
private const val KeyGnssType = "gnss_type"
private const val KeySensors = "sensors"

/** The receiver type by name, else from the flags older builds wrote. */
private fun gnssType(name: String?, nmea: Boolean): GnssType =
    GnssType.entries.firstOrNull { it.name == name } ?: if (nmea) GnssType.NMEA else GnssType.RACEBOX

/** The receiver of the recording that was running when the recorder process was killed. */
private fun wantedGnss(wanted: SharedPreferences): GnssRef? = when {
    wanted.getBoolean(KeyGnssDemo, false) -> GnssRef.demoOf(gnssType(wanted.getString(KeyGnssType, null), false))
    else -> wanted.getString(KeyRaceBoxAddress, null)?.let {
        val type = gnssType(wanted.getString(KeyGnssType, null), wanted.getBoolean(KeyGnssNmea, false))
        GnssRef(it, wanted.getString(KeyRaceBoxName, "").orEmpty(), type)
    }
}

/**
 * The position channels of a session on [gnss]: Demo GNSS positions are stored as made up, never as
 * measured (FBBD-174); the phone's GPS as a phone's, never as a receiver's (FBBD-275).
 */
private fun gnssChannels(gnss: GnssRef) = when {
    gnss.demo -> FakeGnssSource.Channels
    gnss.type == GnssType.PHONE -> PhoneGnssChannels.GNSS
    else -> GnssChannels.ALL
}

/** The receiver a start intent names: Demo GNSS, a RaceBox, another receiver, or none. */
private fun gnssFrom(intent: Intent): GnssRef? = when {
    intent.getBooleanExtra(RecorderProtocol.EXTRA_GNSS_DEMO, false) ->
        GnssRef.demoOf(gnssType(intent.getStringExtra(RecorderProtocol.EXTRA_GNSS_TYPE), false))
    else -> intent.getStringExtra(RecorderProtocol.EXTRA_RACEBOX_ADDRESS)?.let {
        val type = gnssType(
            intent.getStringExtra(RecorderProtocol.EXTRA_GNSS_TYPE),
            intent.getBooleanExtra(RecorderProtocol.EXTRA_GNSS_NMEA, false),
        )
        GnssRef(it, intent.getStringExtra(RecorderProtocol.EXTRA_RACEBOX_NAME).orEmpty(), type)
    }
}

/**
 * G for the track screen (UI-38): the RaceBox accelerometer, or a RaceChrono DIY device's $RC2/$RC3
 * lines over Bluetooth or Wi-Fi (FBBD-115). Other receivers have none: the VBOX Sport's G channels
 * and the Dragy Pro's IMU are not read (FBBD-116, FBBD-117).
 */
private fun accelIdsFor(gnss: GnssRef): Pair<Int, Int>? = when (gnss.type) {
    GnssType.RACEBOX -> RaceBoxChannels.ACCEL_X.id to RaceBoxChannels.ACCEL_Y.id
    GnssType.NMEA, GnssType.TCP -> RcChannels.ACCEL_X.id to RcChannels.ACCEL_Y.id
    else -> null
}

/** How old the receiver's fixes are, so cues allow for it (FBBD-229); logged as it is measured. */
private fun fixAgeFor(gnss: GnssRef): LiveFixAge? = accelIdsFor(gnss)?.let { (x, y) ->
    LiveFixAge(setOf(x, y)) { age -> DebugLog.i("RecorderService", "gnss fix age ${age.text}") }
}

private fun sensorsFrom(intent: Intent): List<SensorRef> =
    intent.getStringArrayExtra(RecorderProtocol.EXTRA_SENSORS).orEmpty().mapNotNull(SensorRef::decode)

private fun wantedSensors(wanted: SharedPreferences): List<SensorRef> =
    wanted.getString(KeySensors, "").orEmpty().lines().mapNotNull(SensorRef::decode)

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

    private class Active(
        val recording: Recording,
        val end: RecordingEnd,
        val tracker: LiveTracker,
        val timing: SessionTiming,
        val coach: CoachLink?,
        val device: DeviceChecks,
    )

    private val main = Handler(Looper.getMainLooper())

    /** Starts and stops run here, off the main thread: both touch the disk and stop can wait for the writer. */
    private val control = Executors.newSingleThreadExecutor { Thread(it, "recorder-control") }
    private lateinit var store: SessionStore
    private lateinit var cpu: CpuLock

    /** Camera reports (FBBD-21) from the camera process are logged into the running recording. */
    private val clients = LiveClients(
        state = ::state,
        onVideo = { data ->
            data.getString(RecorderProtocol.VIDEO_SESSION)?.let { session ->
                afterVideo.report(
                    session,
                    data.getInt(RecorderProtocol.VIDEO_KIND),
                    data.getString(RecorderProtocol.VIDEO_FILE).orEmpty(),
                    data.getString(RecorderProtocol.VIDEO_TEXT).orEmpty(),
                )
            }
            active?.recording?.let { logVideoReport(it, data) { text, ok -> cameraStatus = text to ok } }
        },
        onCoach = { what, data ->
            active?.recording?.let { logCoachReport(it, what, data) { status -> coachStatus = status } }
        },
        onUi = { data -> active?.recording?.let { logUiReport(it, data) } },
    )
    private val wanted by lazy { getSharedPreferences("recorder", Context.MODE_PRIVATE) }

    /** The stop's report waits for the camera's last file (FBBD-247). */
    private val afterVideo = AfterVideo({ r, ms -> main.postDelayed(r, ms) }, main::removeCallbacks)

    @Volatile
    private var active: Active? = null

    @Volatile
    private var lastError: String? = null

    /** The camera's last status for the status strip, for the running recording. Main thread. */
    private var cameraStatus: Pair<String, Boolean>? = null

    /** The coach line, for the running recording (FBBD-98). Main thread. */
    private var coachStatus: CoachStatus = CoachStatus.None

    /** The session a restart after a kill carries on from, for the next recording to name (FBBD-183). */
    @Volatile
    private var restartOf: String? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(sessionsDir(this))
        cpu = CpuLock(this)
        createRecordingChannel(this)
    }

    override fun onBind(intent: Intent): IBinder = clients.messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.i(Tag, "command ${intent?.action ?: "restart after the process was killed"}")
        when (intent?.action) {
            RecorderProtocol.ACTION_START -> {
                lastError = null
                val car = intent.getStringExtra(RecorderProtocol.EXTRA_CAR).orEmpty()
                val track = intent.getStringExtra(RecorderProtocol.EXTRA_TRACK).orEmpty()
                if (goForeground()) startRecording(car, track, gnssFrom(intent), sensorsFrom(intent))
            }
            RecorderProtocol.ACTION_STOP -> stopRecording()
            // Restarted by the system after this process was killed (START_STICKY). If a recording was
            // running, the old session is recovered at the next app start and a new one carries on.
            null -> if (wanted.getBoolean(KeyWanted, false) && goForeground()) {
                val car = wanted.getString(KeyCar, "").orEmpty()
                restartOf = wanted.getString(KeySession, null)
                startRecording(car, wanted.getString(KeyTrack, "").orEmpty(), wantedGnss(wanted), wantedSensors(wanted))
            } else {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    /** As location and connected device, each only while Android accepts it (startRecorderForeground, FBBD-34). */
    @Suppress("TooGenericExceptionCaught") // Not allowed to go foreground (Android 12+), or no type is allowed.
    private fun goForeground(): Boolean = try {
        val info = active?.recording?.info
        val notification = recordingNotification(
            this,
            info?.startUtcMillis ?: System.currentTimeMillis(),
            info?.let(::notificationDetail).orEmpty(),
        )
        startRecorderForeground(NotificationId, notification)
        true
    } catch (e: RuntimeException) {
        DebugLog.e(Tag, "could not go foreground", e)
        lastError = "Could not start recording: ${e.message}"
        stopSelf()
        false
    }

    @Suppress("TooGenericExceptionCaught") // Any startup failure is reported to the UI, never a crash.
    private fun startRecording(car: String, track: String, gnss: GnssRef?, sensors: List<SensorRef>) = control.execute {
        // Running, or still finishing after a stop (Audit A03): one writer at a time.
        active?.let { return@execute DebugLog.i(Tag, "start ignored: recording ${it.recording.id} has not ended") }
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
            val sources = Sources.create(this, gnss, sensors)
            val info = SessionInfo(
                startUtcMillis = System.currentTimeMillis(),
                car = car,
                track = track,
                devices = sources.map { it.name },
                channels = gnssChannels(gnss),
            )
            val recording = Recorder(store).start(info).also { logTo(it, gnss.name) }
            val tracker = LiveTracker(
                recording.id,
                info.startUtcMillis,
                recording::nowMicros,
                droppedSamples = { recording.droppedCount },
                accelIds = accelIdsFor(gnss),
            )
            recording.listeners.add(tracker)
            // The circuit list names circuits for tracks not saved yet (FBBD-79). Read at each start, so a
            // newer one the UI process fetched since the last session is used.
            val circuits = CircuitDirectory(circuitsDir(this)).load()
            // Picks the track at the first fix and times laps there (FBBD-55, FBBD-12). It runs on the
            // listener thread, so a timing failure is logged in the session and never stops it.
            // Each lap is logged with the predicted lap time shown before its line (FBBD-183).
            val timing = SessionTiming({ TrackStore(tracksDir(this)).all() }, circuits, onLap = { lap, predicted ->
                recording.event(EventKinds.LAP_LINE, lapLineText(lap, predicted))
            }) { found -> recording.event(TrackEvent.KIND, TrackEvent.text(found)) }
            recording.listeners.add(timing)
            // The live coach runs in its own process, fed from here (FBBD-98); off in settings, it is not started.
            val coach = if (coachSettings(this).read().verbosity == Verbosity.Off) {
                null
            } else {
                CoachLink(this, recording.id, recording.startNanos, timing, clients.messenger, fixAgeFor(gnss))
            }
            coach?.let(recording.listeners::add)
            // Ends the recording here only when its writer has stopped, also for stops the recorder makes
            // itself (storage full, write error), with the writer's reason (Audit A03).
            val end = RecordingEnd(recording) { reason -> main.post { onEnded(recording, reason) } }
            active = Active(recording, end, tracker, timing, coach, DeviceChecks(this, recording).also { it.start() })
            carryOn(wanted, recording, restartOf.also { restartOf = null })
            sources.forEach(recording::attach)
            main.post {
                // Already ended on its own (storage full): no wake lock or coach binding may outlive it.
                if (active?.recording !== recording) return@post
                cameraStatus = null
                coachStatus = CoachStatus.None
                coach?.bind()
                cpu.acquire()
                rememberWanted(wanted, car, track, gnss, sensors)
                getSystemService(NotificationManager::class.java)
                    .notify(NotificationId, recordingNotification(this, info.startUtcMillis, notificationDetail(info)))
            }
        } catch (e: Exception) {
            lastError = "Could not start recording: $e".also { DebugLog.e(Tag, it, e) }
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
            } else if (!a.end.stop(UserStop)) {
                // Still finishing (Audit A03): the service, its wake lock and the session stay until the
                // writer has stopped, and no new recording starts meanwhile.
                DebugLog.w(Tag, "recording ${a.recording.id} still finishing after ${RecordingEnd.StopWaitMs} ms")
            }
        }
    }

    /** Main thread. Runs once per recording, whichever of the user stop or the recorder's own stop comes first. */
    private fun onEnded(recording: Recording, reason: String) {
        val ended = active
        if (ended == null || ended.recording !== recording) return
        ended.coach?.unbind()
        ended.device.stop()
        active = null
        DebugLog.i(Tag, "recording ${recording.id} ended: $reason")
        DebugLog.session = null
        afterVideo.whenClosed(recording.id) { logQuality(this, control, recording.id) }
        // How the app's processes ended during the drive, read after it, so its ANRs are known (FBBD-41).
        control.execute { StartupLog.logExits(this) }
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
        a?.timing?.detection?.let { RecorderProtocol.putPlace(state, it) }
        if (a != null) {
            coachStatus.corner?.let { state.putString(RecorderProtocol.KEY_COACH_CORNER, it) }
            coachStatus.text?.let { state.putString(RecorderProtocol.KEY_COACH_TEXT, it) }
            state.putBoolean(RecorderProtocol.KEY_COACH_PRAISE, coachStatus.praise)
            state.putBoolean(RecorderProtocol.KEY_COACH_DIM, coachStatus.dim)
        }
        if (a != null) {
            ObdSource.current?.status()?.let { RecorderProtocol.putObd(state, it) }
            HeartRateSource.current?.status()?.let { RecorderProtocol.putHeartRate(state, it) }
        }
        a?.timing?.state?.let { RecorderProtocol.putTiming(state, it) }
        lastError?.let { state.putString(RecorderProtocol.KEY_ERROR, it) }
        return state
    }

    override fun onDestroy() {
        DebugLog.i(Tag, "service destroyed, recording running: ${active != null}")
        active?.coach?.unbind()
        active?.device?.stop()
        clients.close()
        // Only reached with a recording running if the system destroys the service: finish it cleanly.
        active?.recording?.stop("service_destroyed", DestroyStopTimeoutMs)
        active = null
        cpu.release()
        control.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val Tag = "RecorderService"
        private const val UserStop = "user"
        private const val KeyWanted = "wanted"
        private const val KeyCar = "car"

        /** The session being recorded, so a restart after a kill can name it (FBBD-183). */
        private const val KeySession = "session"

        /** Shown on the track screen when a start has no GNSS receiver; nothing is recorded. */
        const val NoReceiver = "No GNSS receiver. Add one in Settings › Equipment."
        private const val KeyTrack = "track"

        /**
         * Names in [recording] the session [previous] the killed process recorded, when it carries one
         * on, so the validation report can measure the data lost (FBBD-183); remembers [recording] for
         * the next restart. Control thread.
         */
        private fun carryOn(wanted: SharedPreferences, recording: Recording, previous: String?) {
            previous?.let { recording.event(EventKinds.RECORDER_RESTART, "previous=$it") }
            wanted.edit().putString(KeySession, recording.id).commit()
        }

        /**
         * Every line of this process now names [recording]; the log holds its events, its GNSS
         * second by second and any heart rate 10 s at a time, so the hardware checks need nothing
         * else (FBBD-183, FBBD-225).
         */
        private fun logTo(recording: Recording, receiver: String) {
            DebugLog.session = recording.id
            val start = recording.info.startUtcMillis
            recording.listeners.add(LoggingListener(recording.id, receiver, start, recording::nowMicros))
            val micro = receiver.startsWith(RaceBoxGatt.MICRO_PREFIX)
            recording.listeners.add(GnssSecondLog(recording.id, start, skipsWithoutFix = micro))
            recording.listeners.add(HeartRateLog(recording.id))
        }

        /**
         * The session's report in the drive's log (FBBD-183), made on [control] once the recording
         * is closed. Made here, it is also ready for the session screen.
         */
        private fun logQuality(context: Context, control: Executor, id: String) = control.execute {
            val report = qualityReports(context).load(id) ?: return@execute
            report.logLines().forEach { DebugLog.forSession(id, CheckLog.QUALITY, it) }
        }

        /** What a restart after this process is killed records again (START_STICKY). Main thread. */
        private fun rememberWanted(
            wanted: SharedPreferences,
            car: String,
            track: String,
            gnss: GnssRef,
            sensors: List<SensorRef>,
        ) {
            wanted.edit().putBoolean(KeyWanted, true).putString(KeyCar, car).putString(KeyTrack, track)
                .putString(KeyRaceBoxAddress, gnss.address).putString(KeyRaceBoxName, gnss.name)
                .putBoolean(KeyGnssDemo, gnss.demo).putString(KeyGnssType, gnss.type.name)
                .putString(KeySensors, sensors.joinToString("\n") { it.encode() }).commit()
        }
    }
}
