package com.flappedear.betterdriver.recorder

/**
 * A logged channel. [id] is unique within a session and is what samples refer to.
 * [decimals] is the precision used when the channel is exported (VBO).
 */
data class ChannelDef(
    val id: Int,
    val name: String,
    val unit: String,
    val source: String,
    val decimals: Int = 3,
) {
    init {
        require(id in 0..MaxChannelId) { "channel id out of range: $id" }
        require(decimals in 0..MaxDecimals) { "decimals out of range: $decimals" }
    }
}

private const val MaxChannelId = 0xFFFF
private const val MaxDecimals = 9

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
data class SampleSet(val tMicros: Long, val values: List<ChannelValue>)

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

/** What is known about a session when it starts. Written to disk before the first sample (FBBD-70). */
data class SessionInfo(
    val startUtcMillis: Long,
    val car: String = "",
    val track: String = "",
    val devices: List<String> = emptyList(),
    val startFinish: Gate? = null,
    val channels: List<ChannelDef> = emptyList(),
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
    const val VIDEO_STOP = "video_stop"
    const val SOURCE_ERROR = "source_error"
    const val LISTENER_ERROR = "listener_error"
    const val STORAGE_LOW = "storage_low"
    const val STORAGE_FULL = "storage_full"
    const val DROPPED_SAMPLES = "dropped_samples"
}
