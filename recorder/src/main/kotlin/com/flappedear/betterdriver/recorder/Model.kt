package com.flappedear.betterdriver.recorder

/**
 * A logged channel. [id] is unique within a session and is what samples refer to.
 * [decimals] is the precision used when the channel is exported (VBO). [provenance] is where its
 * values come from (FBBD-174); by default what its [source] and [name] say ([Provenance.derive]).
 */
data class ChannelDef(
    val id: Int,
    val name: String,
    val unit: String,
    val source: String,
    val decimals: Int = 3,
    val provenance: Provenance = Provenance.derive(source, name),
) {
    init {
        require(id in 0..MaxChannelId) { "channel id out of range: $id" }
        require(decimals in 0..MaxDecimals) { "decimals out of range: $decimals" }
        // Checked here, on the caller's thread, so a bad definition can never fail the writer.
        require(name.length <= MaxNameChars && unit.length <= MaxNameChars && source.length <= MaxNameChars) {
            "channel name, unit and source must be at most $MaxNameChars characters"
        }
    }
}

private const val MaxChannelId = 0xFFFF
private const val MaxDecimals = 9

/** At most 3 encoded bytes per char, far below the 65,535-byte limit of the record format. */
internal const val MaxNameChars = 256

/** Most values one [SampleSet] can hold in the record format. */
internal const val MaxValuesPerSet = 0xFFFF

/** One value of one channel. */
data class ChannelValue(val channelId: Int, val value: Double) {
    init {
        require(channelId in 0..MaxChannelId) { "channel id out of range: $channelId" }
    }
}

/**
 * Values taken at the same instant. [tMicros] is microseconds since the session started,
 * on the recorder's monotonic clock.
 */
data class SampleSet(val tMicros: Long, val values: List<ChannelValue>) {
    init {
        require(values.size <= MaxValuesPerSet) { "too many values in one set: ${values.size}" }
    }
}

/**
 * [values] packed into two arrays, for sample sets held in bulk: a session read back holds millions
 * of values, and one [ChannelValue] object each ran a 63-minute drive's export out of the app's
 * 256 MB (FBBD-232). Reading an element makes a short-lived [ChannelValue]; equality is a list's.
 */
fun packedValues(values: List<ChannelValue>): List<ChannelValue> =
    values as? PackedValues ?: PackedValues(
        CharArray(values.size) { values[it].channelId.toChar() },
        DoubleArray(values.size) { values[it].value },
    )

/** Channel ids fit a char: they are at most [MaxChannelId]. */
internal class PackedValues(
    private val ids: CharArray,
    private val numbers: DoubleArray,
) : AbstractList<ChannelValue>(), RandomAccess {
    override val size: Int get() = ids.size

    override fun get(index: Int) = ChannelValue(ids[index].code, numbers[index])
}

/** A timestamped event, such as "gnss_lost" or "lap". */
data class RecorderEvent(val tMicros: Long, val kind: String, val text: String)

/** A start/finish or split gate: a line between two points, in degrees. */
data class Gate(
    val name: String,
    val lat1: Double,
    val lon1: Double,
    val lat2: Double,
    val lon2: Double,
)

/**
 * Where an imported session came from (FBBD-164): [format] as shown on its card ("VBO", "GPX",
 * "RaceChrono CSV") and the [fileName] it was read from. [receiver] is the device or app the file
 * names as its maker, empty when it names none; [sampleRateHz] the file's typical position rate,
 * null when not known (an import made before FBBD-174).
 */
data class ImportOrigin(
    val format: String,
    val fileName: String,
    val receiver: String = "",
    val sampleRateHz: Double? = null,
)

/**
 * What is known about a session when it starts. Written to disk before the first sample (FBBD-70).
 * [imported] is set for a session made from another app's file rather than recorded here (FBBD-164).
 */
data class SessionInfo(
    val startUtcMillis: Long,
    val car: String = "",
    val track: String = "",
    val devices: List<String> = emptyList(),
    val startFinish: Gate? = null,
    val channels: List<ChannelDef> = emptyList(),
    val imported: ImportOrigin? = null,
)

/**
 * Well-known GNSS channels. The VBO exporter maps these to Racelogic standard channels,
 * and a set containing [LAT] and [LON] produces one VBO data row.
 */
object GnssChannels {
    const val SOURCE = "gnss"
    val UTC_MS = ChannelDef(1, "gnss_utc_ms", "ms", SOURCE, 0)
    val LAT = ChannelDef(2, "gnss_lat", "deg", SOURCE, 8)
    val LON = ChannelDef(3, "gnss_lon", "deg", SOURCE, 8)
    val SPEED_KMH = ChannelDef(4, "gnss_speed", "km/h", SOURCE, 3)
    val HEADING = ChannelDef(5, "gnss_heading", "deg", SOURCE, 2)
    val ALT_M = ChannelDef(6, "gnss_alt", "m", SOURCE, 2)
    val SATS = ChannelDef(7, "gnss_sats", "", SOURCE, 0)

    val ALL = listOf(UTC_MS, LAT, LON, SPEED_KMH, HEADING, ALT_M, SATS)
    val IDS = ALL.map { it.id }.toSet()
}

/** Where samples go. Implemented by the recorder; data sources only ever see this. */
interface SampleSink {
    fun defineChannel(def: ChannelDef)
    fun submit(set: SampleSet)
    fun event(kind: String, text: String)
    /** Microseconds since session start on the recorder's clock, for sources that stamp their own sets. */
    fun nowMicros(): Long
}

/** A GNSS receiver, OBD reader or fake. It pushes into the sink on its own thread. */
interface DataSource {
    val name: String
    fun start(sink: SampleSink)
    fun stop()
}

/** Event kinds the recorder and its sources write. Free text goes in [RecorderEvent.text]. */
object EventKinds {
    /**
     * A camera started recording; text is "camera=<name> file=<file name>".
     * Its tMicros is the video's offset to session time.
     */
    const val VIDEO_START = "video_start"

    /**
     * Text "camera=<name> file=<file name> media_us=<n>": at tMicros the file held n microseconds
     * of media. Refines the start and gives an end if the camera dies (video.VideoTimeline). The phone
     * camera adds "frame_us=<session time its newest frame was captured, from the sensor timestamp>"
     * (FBBD-21), so its sync can be measured against frame times rather than report times.
     */
    const val VIDEO_SYNC = "video_sync"

    /** Text "camera=<name> file=<file name> reason=<why>". */
    const val VIDEO_STOP = "video_stop"
    const val SOURCE_ERROR = "source_error"
    const val LISTENER_ERROR = "listener_error"
    const val STORAGE_LOW = "storage_low"
    const val STORAGE_FULL = "storage_full"
    const val DROPPED_SAMPLES = "dropped_samples"

    /**
     * The recorder process was killed and Android restarted it, so this session carries on from an
     * earlier one (FBBD-72, FBBD-183). Text "previous=<session id>", the session it carries on.
     */
    const val RECORDER_RESTART = "recorder_restart"

    /**
     * Android's thermal status changed while the phone camera ran (FBBD-21, FBBD-183). Text
     * "status=<PowerManager.THERMAL_STATUS_*> headroom=<forecast or n/a>", then " heat=ignored" when
     * the camera ignored the temperature for a heat stress test (FBBD-231).
     */
    const val THERMAL_STATUS = "thermal_status"

    /**
     * A coach cue's sound started (FBBD-27, FBBD-183). Text "seq=<cue seq> path=<live|clip>
     * asked_us=<session time the voice was asked> route=<headset|speaker>", and from FBBD-47 on also
     * "ready_us=<session time its audio was ready> device=<output type> focus=<granted|refused>
     * out_delay_ms=<the output's own delay, or none>", and from FBBD-49's automatic measurement on
     * "out_delay_src=<measured|set>": measured at the session's start, or set in the coach settings.
     * The event time is the start.
     */
    const val COACH_VOICE = "coach_voice"

    /**
     * Live timing finished a lap (FBBD-12, FBBD-183). Text "lap=<n> kind=<Out|Timed|In> time_ms=<lap
     * time> predicted_ms=<predicted lap time shown just before the line, or none>".
     */
    const val LAP_LINE = "lap_line"

    /**
     * The phone's health every 30 s while recording (FBBD-46, FBBD-183). Text "battery_c=<battery
     * temperature, °C> battery_pct=<charge> plugged=<ac|usb|wireless|none> current_ma=<battery current
     * as the phone reports it, or n/a> thermal=<Android thermal status> headroom=<forecast or n/a>".
     */
    const val DEVICE = "device"

    /** The screen went off or came on while recording (FBBD-63). Text "state=<off|on>". */
    const val SCREEN = "screen"

    /**
     * The track screen's frames over a stretch of the recording (FBBD-41, FBBD-62), from JankStats.
     * Text "frames=<n> janky=<n> frozen=<n over 700 ms> worst_ms=<n> hist=<counts per [FrameBuckets]>".
     */
    const val UI_FRAMES = "ui_frames"

    /**
     * The track screen's GNSS-lost banner was shown or cleared (FBBD-64). Text "state=<shown|cleared>
     * fix_age_us=<age of the last fix the screen knew>". The event time is when the screen changed.
     */
    const val GNSS_BANNER = "gnss_banner"

    /**
     * A GoPro acknowledged a start or stop command (FBBD-82, FBBD-22); the event time is the
     * acknowledgement. Text "camera=<id> action=<start|stop> result=<ok|failed>".
     */
    const val GOPRO = "gopro"

    /**
     * A video file was placed on the session's timeline after it, or refused (FBBD-22). Text
     * "camera=<id> file=<name> result=<aligned|rejected> method=<alignment method> offset_us=<session
     * time of media time 0>".
     */
    const val VIDEO_IMPORT = "video_import"

    /**
     * One stretch of an imported video aligned on its own against the session's GNSS (FBBD-82): how
     * far its offset is from the whole file's shows the camera's drift. The event time is the stretch's
     * middle. Text "camera=<id> file=<name> offset_us=<this stretch's offset> clip_offset_us=<the file's>".
     */
    const val VIDEO_PROBE = "video_probe"

    /** A coach cue's sound ended (FBBD-47). Text "seq=<cue seq> end=<done|stopped|error>". */
    const val COACH_VOICE_END = "coach_voice_end"

    /** The session was exported as VBO (FBBD-14). Text "rows=<data rows> samples=<GNSS sets> ms=<time taken>". */
    const val VBO_EXPORT = "vbo_export"
}

/** Upper edges in ms of the frame-time buckets of [EventKinds.UI_FRAMES]; a last bucket takes the rest. */
val FrameBuckets = listOf(8, 12, 16, 20, 25, 33, 50, 100, 200, 700)
