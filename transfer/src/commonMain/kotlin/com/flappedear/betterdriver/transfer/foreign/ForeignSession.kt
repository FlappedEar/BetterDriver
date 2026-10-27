package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.math.toDegrees
import com.flappedear.betterdriver.math.toRadians
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.timing.live.startFinishGate
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.detectTrack
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What an import made, for the message after it. */
data class ImportedSession(
    val id: String,
    val info: SessionInfo,
    val format: ForeignFormat,
    val points: Int,
    /** Rows or points that could not be read, plus positions out of time order. */
    val skipped: Int,
    val durationMicros: Long,
    val detection: TrackDetection,
)

/** Names and channels of an imported session, the same on Android and iOS (FBBD-164, FBBD-331). */
object ForeignImport {
    /** Event logged in an imported session: its format and what was read. */
    const val ImportedEvent = "imported"

    /** The end reason of an imported session. */
    const val EndReason = "imported"

    /** Source of the channels an import adds. */
    const val Source = "import"

    private const val AccelForwardId = 110
    private const val AccelLateralId = 111
    private const val AccelDecimals = 3

    /**
     * Accelerometer channels under the RaceBox's names, so analysis (the G-G diagram, braking and
     * exit metrics) reads them as it reads a RaceBox's: X forward, Y sideways, in g.
     */
    val AccelForward = ChannelDef(AccelForwardId, "accel_x", "g", Source, AccelDecimals)
    val AccelLateral = ChannelDef(AccelLateralId, "accel_y", "g", Source, AccelDecimals)
    internal val Accel = listOf(AccelForward, AccelLateral)
}

/**
 * Reads the [lines] of a VBO or RaceChrono CSV file into [sink]; a VBO without a date line is put
 * on [fallbackEpochDay]. GPX needs an XML parser, which only the JVM's GpxReader has so far.
 */
internal fun readText(format: ForeignFormat, lines: LineReader, sink: ImportSink, fallbackEpochDay: Long) {
    when (format) {
        ForeignFormat.VBO -> VboReader(fallbackEpochDay).read(lines, sink)
        ForeignFormat.RACECHRONO_CSV -> RaceChronoCsvReader.read(lines, sink)
        ForeignFormat.GPX -> throw ImportFormatException("GPX files cannot be imported on this device yet")
    }
}

/**
 * The files of the session an import builds in staging, on each platform's session store: the
 * Android ChunkWriter and SessionFiles, or the iOS SessionWriter. The same files either way.
 */
internal interface ForeignSessionFiles {
    /** Creates the staged session with [info] as its metadata; returns its id. */
    fun create(info: SessionInfo): String

    /** Opens the session's first chunk with [channels] as its channel table. */
    fun open(channels: List<ChannelDef>)

    fun write(record: Record)

    /** Writes the metadata of session [id] again. */
    fun writeMeta(id: String, info: SessionInfo)

    /** Flushes, syncs and closes the chunk. */
    fun close()

    /** Writes finished.properties for session [id], after [close]. */
    fun writeFinished(id: String, reason: String, endMicros: Long)

    /** Closes what is open after a failure, without throwing. */
    fun abort()
}

/** Writes the points a reader finds as a session, as the recorder would have. */
@Suppress("TooManyFunctions") // The sink's calls, plus noting each channel's provenance (FBBD-174).
internal class ForeignSessionWriter(
    private val files: ForeignSessionFiles,
    private val origin: ImportOrigin,
    private val tracks: List<Track>,
    private val directory: CircuitList,
) : ImportSink {
    private var extraDefs: List<ChannelDef> = emptyList()
    private var opened = false
    private var id: String? = null
    private var info: SessionInfo? = null
    private var detection: TrackDetection = TrackDetection.Unknown
    private var firstUtc = 0L
    private var lastT = -1L
    private var points = 0
    private var skipped = 0

    /** Held back one point, so a segment's first point can take its speed from the second. */
    private var pending: ImportPoint? = null
    private var lastHeading = Double.NaN
    private var forwardSeen = false
    private var lateralSeen = false

    /** Some point's speed or heading was worked out from positions rather than read from the file. */
    private var speedCalculated = false
    private var headingCalculated = false
    private var receiver = ""
    private var accelerationProvenance: Provenance? = null
    private val channelProvenance = HashMap<Int, Provenance>()

    /**
     * How many intervals between written points took each number of milliseconds, for the rate:
     * a fixed array, longer intervals counted in its last slot, so no file can make it grow.
     */
    private val intervals = IntArray(MaxIntervalMs + 1)

    override fun extras(columns: List<ExtraColumn>) {
        if (opened) return // too late: the channel table is written
        val used = (GnssChannels.ALL + ForeignImport.Accel).map { it.name }.toMutableSet()
        extraDefs = columns.take(ImportLimits.MaxExtraChannels).mapIndexed { i, c ->
            var name = c.name.replace(Whitespace, "_").take(NameChars).ifEmpty { "channel_${i + 1}" }
            if (!used.add(name)) name = "import_$name".take(NameChars).also { used.add(it) }
            val def = ChannelDef(FirstExtraId + i, name, c.unit.take(NameChars), ForeignImport.Source, ExtraDecimals)
            c.provenance?.let { def.copy(provenance = it) } ?: def
        }
    }

    override fun skipped() {
        skipped++
    }

    override fun receiver(name: String) {
        receiver = name.take(NameChars)
    }

    override fun accelerationSource(provenance: Provenance) {
        accelerationProvenance = provenance
    }

    override fun channelSource(channelId: Int, provenance: Provenance) {
        channelProvenance[channelId] = provenance
    }

    override fun point(p: ImportPoint) {
        val prev = pending
        when {
            !valid(p) -> skipped++
            prev == null -> pending = p
            p.utcMicros <= prev.utcMicros -> skipped++ // out of time order, or a duplicate
            else -> advance(prev, p)
        }
    }

    /** Writes [prev], now that [p] (later, valid) can fill in what it lacks, and holds [p] back. */
    private fun advance(prev: ImportPoint, p: ImportPoint) {
        val sameSegment = p.segment == prev.segment
        val derived = if (sameSegment) derive(prev, p) else null
        if (derived != null) noteCalculated(prev, p)
        val current = if (derived == null) p else p.copy(
            speedKmh = p.speedKmh.takeUnless { it.isNaN() } ?: derived.first,
            headingDeg = p.headingDeg.takeUnless { it.isNaN() } ?: derived.second,
        )
        // A segment's first point has no point before it: it takes the speed to the next one.
        val first = if (derived == null) prev else prev.copy(
            speedKmh = prev.speedKmh.takeUnless { it.isNaN() } ?: derived.first,
            headingDeg = prev.headingDeg.takeUnless { it.isNaN() } ?: derived.second,
        )
        emit(first)
        pending = current
    }

    /** Notes when [a] or [b] takes a speed or heading from [derive] because the file has none. */
    private fun noteCalculated(a: ImportPoint, b: ImportPoint) {
        if (a.speedKmh.isNaN() || b.speedKmh.isNaN()) speedCalculated = true
        if (a.headingDeg.isNaN() || b.headingDeg.isNaN()) headingCalculated = true
    }

    /** A speed [p] lacks is written as 0, a heading it lacks as the last one: neither is measured. */
    private fun noteMissing(p: ImportPoint) {
        if (p.speedKmh.isNaN()) speedCalculated = true
        if (p.headingDeg.isNaN() && !lastHeading.isNaN()) headingCalculated = true
    }

    /** Speed (km/h) and heading (deg, NaN when standing) from [a] to [b]. */
    private fun derive(a: ImportPoint, b: ImportPoint): Pair<Double, Double> {
        val seconds = (b.utcMicros - a.utcMicros) / MicrosPerSecond
        val metres = distance(a, b)
        val heading = if (metres >= MinMoveMetres) bearing(a, b) else Double.NaN
        return metres / seconds * KmhPerMs to heading
    }

    private fun emit(p: ImportPoint) {
        if (points >= ImportLimits.MaxPoints) {
            throw ImportFormatException("the file has more than ${ImportLimits.MaxPoints} positions")
        }
        if (!opened) open(p)
        // Kept in time order: pending already dropped anything not after the point before it.
        val t = p.utcMicros - firstUtc
        if (t <= lastT) {
            skipped++
            return
        }
        if (lastT >= 0) intervals[minOf((t - lastT) / MicrosPerMilliLong, MaxIntervalMs.toLong()).toInt()]++
        lastT = t
        files.write(Record.Samples(SampleSet(t, valuesOf(p))))
        points++
    }

    /** The channel values of [p]; heading is held while standing, speed is 0 where unknown. */
    private fun valuesOf(p: ImportPoint): List<ChannelValue> {
        noteMissing(p)
        if (!p.headingDeg.isNaN()) lastHeading = p.headingDeg
        val values = ArrayList<ChannelValue>(DefaultValues + p.extras.size)
        values += ChannelValue(GnssChannels.UTC_MS.id, p.utcMicros / MicrosPerMilli)
        values += ChannelValue(GnssChannels.LAT.id, p.lat)
        values += ChannelValue(GnssChannels.LON.id, p.lon)
        values += ChannelValue(GnssChannels.SPEED_KMH.id, p.speedKmh.takeUnless { it.isNaN() } ?: 0.0)
        if (!lastHeading.isNaN()) values += ChannelValue(GnssChannels.HEADING.id, lastHeading)
        if (p.altM.isFinite()) values += ChannelValue(GnssChannels.ALT_M.id, p.altM)
        if (p.sats.isFinite()) values += ChannelValue(GnssChannels.SATS.id, p.sats)
        if (p.accelForwardG.isFinite()) {
            forwardSeen = true
            values += ChannelValue(ForeignImport.AccelForward.id, p.accelForwardG)
        }
        if (p.accelLateralG.isFinite()) {
            lateralSeen = true
            values += ChannelValue(ForeignImport.AccelLateral.id, p.accelLateralG)
        }
        for (i in extraDefs.indices) {
            val v = p.extras.getOrNull(i) ?: continue
            if (v.isFinite()) values += ChannelValue(extraDefs[i].id, v)
        }
        return values
    }

    /** Creates the staged session at the first position, with the track detected there. */
    private fun open(p: ImportPoint) {
        firstUtc = p.utcMicros
        detection = detectTrack(GeoPoint(p.lat, p.lon), tracks, directory)
        val saved = (detection as? TrackDetection.Saved)?.track
        val channels = GnssChannels.ALL + ForeignImport.Accel + extraDefs
        val sessionInfo = SessionInfo(
            startUtcMillis = p.utcMicros.floorDiv(MicrosPerMilliLong),
            track = detection.placeName.orEmpty(),
            devices = listOf("Imported ${origin.format}"),
            startFinish = saved?.startFinishGate(),
            channels = channels,
            imported = origin,
        )
        id = files.create(sessionInfo)
        info = sessionInfo
        opened = true
        files.open(channels)
        // As a recording logs it at its first fix, so analysis and sharing find the track (FBBD-55, FBBD-80).
        files.write(Record.Event(RecorderEvent(0, TrackEvent.KIND, TrackEvent.text(detection))))
    }

    fun finish(format: ForeignFormat): ImportedSession {
        pending?.let { last ->
            pending = null
            if (last.speedKmh.isNaN()) speedCalculated = true
            emit(if (last.speedKmh.isNaN()) last.copy(speedKmh = 0.0) else last)
        }
        if (points < MinPoints) throw ImportFormatException("the file has no positions with times")
        check(opened)
        val sessionId = checkNotNull(id)
        val sessionInfo = withProvenance(checkNotNull(info), format)
        // The metadata was written at the first point; now it can say where each channel came from.
        files.writeMeta(sessionId, sessionInfo)
        val note = "format=${format.label} points=$points skipped=$skipped accel=${forwardSeen || lateralSeen}"
        files.write(Record.Event(RecorderEvent(lastT, ForeignImport.ImportedEvent, note)))
        files.write(Record.End(lastT, ForeignImport.EndReason))
        files.close()
        opened = false
        files.writeFinished(sessionId, ForeignImport.EndReason, lastT)
        return ImportedSession(sessionId, sessionInfo, format, points, skipped, lastT, detection)
    }

    /**
     * [info] with each channel's provenance and the file's maker and rate (FBBD-174): the GNSS
     * channels as the file says they were made or else as the format's receiver measured them,
     * except speed or heading the file lacked somewhere, which are calculated; acceleration and
     * extra columns as the file says, or their defaults.
     */
    private fun withProvenance(info: SessionInfo, format: ForeignFormat): SessionInfo {
        val measured = if (format == ForeignFormat.VBO) {
            Provenance.MEASURED_GNSS_RECEIVER
        } else {
            Provenance.MEASURED_GPS
        }
        val channels = info.channels.map { def ->
            val provenance = when {
                def in ForeignImport.Accel -> accelerationProvenance ?: def.provenance
                def.id !in GnssChannels.IDS -> def.provenance
                def.id == GnssChannels.SPEED_KMH.id && speedCalculated -> Provenance.CALCULATED
                def.id == GnssChannels.HEADING.id && headingCalculated -> Provenance.CALCULATED
                else -> channelProvenance[def.id] ?: measured
            }
            def.copy(provenance = provenance)
        }
        val source = origin.copy(receiver = receiver, sampleRateHz = sampleRateHz())
        return info.copy(channels = channels, imported = source)
    }

    /** Positions per second at the median interval between them, or null with none. */
    private fun sampleRateHz(): Double? {
        val total = intervals.sum()
        var seen = 0
        val median = intervals.indices.firstOrNull { ms ->
            seen += intervals[ms]
            total > 0 && seen * 2 >= total
        }
        return median?.takeIf { it > 0 }?.let { MillisPerSecond / it }
    }

    /** Closes the chunk after a failure; the staged folder is then removed. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun abort() {
        try {
            if (opened) files.abort()
        } catch (_: Exception) {
            // Already failing; the staged folder is removed next.
        }
        opened = false
    }

    private companion object {
        private const val MaxLatitude = 90.0
        private const val MaxLongitude = 180.0
        private const val FirstExtraId = 1000
        private const val ExtraDecimals = 3
        private const val NameChars = 64
        private const val MinPoints = 2
        private const val DefaultValues = 9
        private const val MicrosPerSecond = 1_000_000.0
        private const val MicrosPerMilli = 1_000.0
        private const val MicrosPerMilliLong = 1_000L
        private const val MillisPerSecond = 1_000.0
        private val Whitespace = Regex("\\s+")

        /** Intervals from this many milliseconds up count as this long for the rate (0.1 Hz). */
        private const val MaxIntervalMs = 10_000
        private const val KmhPerMs = 3.6
        private const val MinMoveMetres = 0.5
        private const val EarthRadiusMetres = 6_371_008.8
        private const val FullCircle = 360.0

        /** A real position: finite, in range, and not 0°, 0° (a receiver without a fix). */
        private fun valid(p: ImportPoint) = p.lat.isFinite() && p.lon.isFinite() &&
            p.lat in -MaxLatitude..MaxLatitude && p.lon in -MaxLongitude..MaxLongitude &&
            !(p.lat == 0.0 && p.lon == 0.0)

        private fun distance(a: ImportPoint, b: ImportPoint): Double {
            val p1 = toRadians(a.lat)
            val p2 = toRadians(b.lat)
            val dp = p2 - p1
            val dl = toRadians(b.lon - a.lon)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EarthRadiusMetres * atan2(sqrt(h), sqrt(1 - h))
        }

        private fun bearing(a: ImportPoint, b: ImportPoint): Double {
            val p1 = toRadians(a.lat)
            val p2 = toRadians(b.lat)
            val dl = toRadians(b.lon - a.lon)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (toDegrees(atan2(y, x)) + FullCircle) % FullCircle
        }
    }
}
