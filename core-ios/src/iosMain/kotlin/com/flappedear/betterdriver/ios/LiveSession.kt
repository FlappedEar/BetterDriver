package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachFixReader
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CoachStatus
import com.flappedear.betterdriver.coach.pedalChannelId
import com.flappedear.betterdriver.coach.rpmChannelId
import com.flappedear.betterdriver.coach.steeringChannelId
import com.flappedear.betterdriver.core.ble.RaceBoxChannels
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.PhoneImuChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.live.LiveTracker
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.circuits.CircuitListException
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.LiveTimingState
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.detectTrack
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

private const val MaxErrorText = 500

/**
 * One recording on the iPhone with what its track screen shows (FBBD-332): the session files as on
 * Android, and the same live values and lap timing as the Android recorder service (LiveTracker,
 * SessionTiming). The links (RaceBox, phone GPS) push into it as a [SampleSink] from their own
 * queues; one lock keeps the live state consistent, and a failure there is logged in the session
 * and never stops the recording (FBBD-69). The live coach rides on it as on Android's recorder (FBBD-333).
 */
@Suppress("TooManyFunctions") // The recording's one Kotlin entry for the app: samples, screen, coach, stop.
class LiveSession private constructor(
    private val recording: Recording,
    private val tracker: LiveTracker,
    private val timing: SessionTiming,
) : SampleSink {
    private val lock = NSRecursiveLock()
    private var liveFailed = false

    // The recorder's low-space warning, for the next look. The recorder calls in with its own lock
    // held, and lap and track events take the recorder's lock inside this session's, so the warning
    // must not take this lock: the links feed the session from several queues (RaceBox or GPS, the
    // phone's accelerometer) and the two orders would deadlock.
    @Volatile
    private var storageLowBytes: Long? = null

    /** The live coach (FBBD-333), once [startCoach] started it; under the lock. */
    private var coach: LiveCoach? = null

    /** The session's channels, to find the pedal, wheel and engine speed by (FBBD-307); under the lock. */
    private val coachChannels = HashMap<Int, ChannelDef>()

    /** Reads each fix with the last pedal, wheel and engine speed, as Android's CoachLink; under the lock. */
    private var coachReader = CoachFixReader(null)

    val id: String get() = recording.id

    /** The session's folder. */
    val dir: String get() = recording.dir

    val isRecording: Boolean get() = recording.isRecording

    /** Why it ended, once it has: "user", "storage_full" or "error". */
    val endReason: String? get() = recording.endReason

    override fun nowMicros(): Long = recording.nowMicros()

    override fun defineChannel(def: ChannelDef) {
        recording.defineChannel(def)
        live {
            tracker.onChannel(def)
            coachChannel(def)
        }
    }

    /** An OBD adapter that connects later brings its pedal, wheel and engine speed then (CoachLink). */
    private fun coachChannel(def: ChannelDef) {
        coachChannels[def.id] = def
        val all = coachChannels.values
        val pedal = pedalChannelId(all)
        val steering = steeringChannelId(all)
        val rpm = rpmChannelId(all)
        val r = coachReader
        if (pedal != r.pedal || steering != r.steering || rpm != r.rpm) {
            coachReader = CoachFixReader(pedal, steering, rpm)
        }
    }

    override fun submit(set: SampleSet) {
        recording.submit(set)
        live {
            tracker.onSamples(set)
            timing.onSamples(set)
            // Read before the coach starts, so the pedal value is there for its first fix.
            val fix = coachReader.read(set)
            coach?.onFix(fix, timing.detection)
        }
    }

    /**
     * Starts the live coach (FBBD-333), as the Android recorder binds its coach when the coach is on:
     * it coaches at a saved track with laps, from the sessions in [sessionsDir] and the references it
     * keeps in [referencesDir], aimed at the line plans the review keeps in [linePlansDir], and says its
     * cues with [speaker]. Its events go into this recording at their own times.
     * [audioDelayFromPhone]: [settings]' delay is the output latency iOS reported.
     */
    @Suppress("LongParameterList") // Where the coach's files are, and how it speaks.
    fun startCoach(
        sessionsDir: String,
        referencesDir: String,
        linePlansDir: String,
        settings: CoachSettings,
        audioDelayFromPhone: Boolean,
        speaker: CoachSpeaker,
    ) {
        val c = LiveCoach(
            recording.id, sessionsDir, referencesDir, linePlansDir, settings, audioDelayFromPhone, speaker,
            recording::nowMicros,
        ) { t, kind, text -> recording.eventAt(t, kind, text) }
        guarded { if (coach == null) coach = c }
    }

    /**
     * The audio output changed mid-session (FBBD-354): the coach's cues from now on allow for [millis],
     * which iOS reported for the new output when [measured]; nothing without a coach.
     */
    fun audioDelay(millis: Int, measured: Boolean) {
        guarded { coach }?.audioDelay(millis, measured)
    }

    /** Waits for the coach's queued work, for tests. */
    internal fun awaitCoach() = guarded { coach }?.awaitIdle()

    override fun event(kind: String, text: String) = recording.event(kind, text)

    /** An event stamped by its source at [tMicros] on the session clock, such as a camera's (PhoneVideoLog). */
    internal fun eventAt(tMicros: Long, kind: String, text: String) = recording.eventAt(tMicros, kind, text)

    /** What the track screen shows now; call it on a timer, so a lost signal shows without new data. */
    fun screen(): LiveScreen {
        // Read before taking the lock: the recorder calls in with its own lock held (storage low).
        val ended = recording.endReason
        return guarded {
            storageLowBytes?.let { tracker.onStatus(RecorderStatus.StorageLow(it)) }
            val live = tracker.snapshot()
            val detection = timing.detection
            LiveScreen(
                live = live,
                timing = timing.state,
                place = detection?.placeName,
                placeSaved = detection is TrackDetection.Saved,
                stoppedReason = ended,
                coach = coach?.takeIf { it.on }?.status,
            )
        }
    }

    /** The recorder's warning that free space is low, for the track screen's alert. */
    private fun storageLow(bytes: Long) {
        storageLowBytes = bytes
    }

    /** Ends the recording cleanly; false if it had already ended. */
    fun stop(): Boolean = recording.stop().also { stopped ->
        if (stopped) live { tracker.onStatus(RecorderStatus.Stopped(recording.endReason ?: "user")) }
    }

    private inline fun <T> guarded(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    // Any failure of the live values must leave the recording running; the first one is logged.
    @Suppress("TooGenericExceptionCaught")
    private inline fun live(body: () -> Unit) = guarded {
        try {
            body()
        } catch (t: Throwable) {
            if (!liveFailed) {
                liveFailed = true
                recording.event(EventKinds.LISTENER_ERROR, t.toString().take(MaxErrorText))
            }
        }
    }

    companion object {
        /**
         * Starts a recording in [sessionsDir], timed against the tracks in [tracksDir] and named from
         * [circuits] when the car is at a track not saved yet. [receiverHasG]: the receiver is a RaceBox,
         * whose accelerometer gives the G tile; otherwise it is the phone's GPS, and the G comes from
         * the phone's own accelerometer (FBBD-337). Throws when the session cannot be created.
         */
        @Throws(Exception::class)
        fun start(
            sessionsDir: String,
            tracksDir: String,
            circuits: CircuitList,
            info: SessionInfo,
            receiverHasG: Boolean,
        ): LiveSession = start(sessionsDir, tracksDir, circuits, info, receiverHasG, RecorderConfig())

        /** As [start] with the recorder's [config], for tests. */
        @Suppress("LongParameterList")
        internal fun start(
            sessionsDir: String,
            tracksDir: String,
            circuits: CircuitList,
            info: SessionInfo,
            receiverHasG: Boolean,
            config: RecorderConfig,
        ): LiveSession {
            val recording = Recorder(SessionStore(sessionsDir), config).start(info)
            val tracker = LiveTracker(
                recording.id,
                info.startUtcMillis,
                recording::nowMicros,
                accelIds = if (receiverHasG) {
                    RaceBoxChannels.ACCEL_X.id to RaceBoxChannels.ACCEL_Y.id
                } else {
                    PhoneImuChannels.ACCEL_X.id to PhoneImuChannels.ACCEL_Y.id
                },
                accelZId = PhoneImuChannels.ACCEL_Z.id.takeIf { !receiverHasG },
            )
            // Picks the track at the first fix and times laps there (FBBD-55, FBBD-12); each lap is logged
            // with the predicted lap time shown before its line (FBBD-183), as on Android.
            val timing = SessionTiming({ TrackFiles.read(tracksDir) }, circuits, onLap = { lap, predicted ->
                recording.event(EventKinds.LAP_LINE, lapLineText(lap, predicted))
            }) { found -> recording.event(TrackEvent.KIND, TrackEvent.text(found)) }
            info.channels.forEach(tracker::onChannel)
            return LiveSession(recording, tracker, timing).also { session ->
                recording.onStorageLow = session::storageLow
            }
        }
    }
}

/** One look at a running recording for the track screen. */
data class LiveScreen(
    val live: LiveSnapshot,
    /** Null until the car is at a saved track with a start/finish line. */
    val timing: LiveTimingState?,
    /** The saved track or directory circuit the car is at, once the first fix has named it. */
    val place: String?,
    val placeSaved: Boolean,
    val stoppedReason: String?,
    /** The coach line (FBBD-333): null when the coach is off, so the tiles take its place (UI-142 B). */
    val coach: CoachStatus? = null,
) {
    val gnss: GnssState get() = live.gnss

    /**
     * The rate on the GNSS status: the receiver's, from the spacing of its recent fixes, else the fixes
     * of the last second (as shownRateHz on Android).
     */
    val shownRateHz: Int get() = if (live.receiverRateHz > 0) live.receiverRateHz else live.fixRateHz
}

/** The saved tracks, one JSON file each (base/tracks/<id>.json), as TrackStore keeps them on Android. */
object TrackFiles {
    private const val Suffix = ".json"

    /** Every readable track in [dir]; a file that cannot be read is skipped and left as it is. */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    @Suppress("TooGenericExceptionCaught") // Any damaged file is skipped, never a crash in Swift.
    fun read(dir: String): List<Track> {
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
        return names.filterIsInstance<String>().filter { it.endsWith(Suffix) }.mapNotNull { name ->
            val text = NSString.stringWithContentsOfFile("$dir/$name", NSUTF8StringEncoding, null)
            try {
                text?.let(TrackJson::decode)
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.displayName.lowercase() }
    }

    /** The saved track or directory circuit at [lat], [lon], for the START button; null when none. */
    fun placeAt(lat: Double, lon: Double, tracks: List<Track>, circuits: CircuitList): String? =
        detectTrack(GeoPoint(lat, lon), tracks, circuits).placeName

    /** The circuit directory from the list bundled with the app; empty when it cannot be read. */
    fun circuits(json: String?): CircuitList = try {
        json?.let(CircuitList::decode) ?: CircuitList.Empty
    } catch (_: CircuitListException) {
        CircuitList.Empty
    }
}
