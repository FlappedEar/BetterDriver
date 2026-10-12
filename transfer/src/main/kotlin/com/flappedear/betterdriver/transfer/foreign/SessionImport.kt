package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.ChunkWriter
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.timing.live.startFinishGate
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.detectTrack
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
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

/**
 * Turns a VBO, GPX or RaceChrono CSV file into an ordinary session in [store] (FBBD-164): the same
 * chunk files, metadata and end marker a recording leaves, with no video, so lap timing, analysis,
 * the review, exports, sharing and backup work on it as on any session. The session's metadata
 * names the source format and file ([SessionInfo.imported]).
 *
 * The file may be gzip-compressed; its format is decided by its content ([ForeignFormat.detect]).
 * Positions are kept in time order (one at or before the previous time is dropped); speed and
 * heading the file lacks are derived from consecutive positions. The track is detected from the
 * first position as a recording does it ([detectTrack]) and logged as the session's track event,
 * so the session is timed against that saved track; tracks are never created from the file.
 *
 * The session is built in [staging] and moved into the list with one rename only when complete,
 * so a failed import leaves nothing behind. Its id is new ([SessionStore.createSession]: start
 * time plus a random suffix) and never one already in [store]. [staging] must be on the same file
 * system as the store. Runs on the caller's thread: call it off the main thread.
 */
class SessionImport(private val store: SessionStore, private val staging: File) {

    /**
     * Imports [input]. [fileName] is shown on the card; [tracks] and [directory] are used for track
     * detection; [onBytes] reports file bytes read, for progress; [beforeAdd] runs just before the
     * session is added and may throw to refuse (a recording started meanwhile). Throws
     * [ImportFormatException] for a file that cannot be imported.
     */
    @Suppress("LongParameterList")
    fun import(
        input: InputStream,
        fileName: String,
        tracks: List<Track>,
        directory: CircuitList,
        onBytes: (Int) -> Unit = {},
        beforeAdd: () -> Unit = {},
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): ImportedSession = try {
        importNow(input, fileName, Context(tracks, directory, beforeAdd, today), onBytes)
    } catch (e: ZipException) {
        throw ImportFormatException("the compressed file is damaged", e)
    } catch (e: EOFException) {
        throw ImportFormatException("the compressed file is cut short", e)
    } catch (e: IllegalArgumentException) {
        throw ImportFormatException("the file has values BetterDriver cannot store: ${e.message}", e)
    }

    private class Context(
        val tracks: List<Track>,
        val directory: CircuitList,
        val beforeAdd: () -> Unit,
        val today: LocalDate,
    )

    private fun importNow(input: InputStream, fileName: String, c: Context, onBytes: (Int) -> Unit): ImportedSession {
        staging.mkdirs()
        val counted = CountingInput(input, ImportLimits.MaxBytes, onBytes)
        val text = decompressed(BufferedInputStream(counted, BufferBytes))
        val format = ForeignFormat.detect(peek(text)) ?: throw ImportFormatException(
            "this is not a file BetterDriver can import " +
                "(a BetterDriver session or track, VBO, GPX or RaceChrono CSV)",
        )
        val root = File(staging, "import-" + UUID.randomUUID())
        val writer = Writer(SessionStore(root), ImportOrigin(format.label, fileName), c.tracks, c.directory)
        try {
            when (format) {
                ForeignFormat.VBO -> VboReader(c.today).read(InputStreamReader(text, Charsets.UTF_8), writer)
                ForeignFormat.GPX -> GpxReader.read(text, writer)
                ForeignFormat.RACECHRONO_CSV ->
                    RaceChronoCsvReader.read(InputStreamReader(text, Charsets.UTF_8), writer)
            }
            val made = writer.finish(format)
            c.beforeAdd()
            add(File(root, made.id))
            return made
        } finally {
            writer.abort()
            root.deleteRecursively()
        }
    }

    /** Moves the finished session [staged] into the list with one rename; its id is never one already there. */
    private fun add(staged: File) {
        val target = store.dir(staged.name)
        if (target.exists()) throw IOException("a session ${staged.name} is already here")
        if (!staged.renameTo(target)) throw IOException("could not add the session")
        SessionFiles.syncDir(store.root)
    }

    /** Unwraps gzip (a .gz export) by its magic number; anything else is returned as is. */
    private fun decompressed(input: BufferedInputStream): InputStream {
        input.mark(2)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        if (b0 != Gzip0 || b1 != Gzip1) return input
        // Counted again after decompressing, so a small file cannot expand without limit.
        val inflated = CountingInput(GZIPInputStream(input, BufferBytes), ImportLimits.MaxBytes)
        return BufferedInputStream(inflated, BufferBytes)
    }

    private fun peek(input: InputStream): ByteArray {
        input.mark(ForeignFormat.PeekBytes)
        val head = ByteArray(ForeignFormat.PeekBytes)
        var n = 0
        try {
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
        } finally {
            input.reset()
        }
        if (n == 0) throw ImportFormatException("the file is empty")
        return head.copyOf(n)
    }

    /** Writes the points a reader finds as a session, as the recorder would have. */
    private class Writer(
        private val staged: SessionStore,
        private val origin: ImportOrigin,
        private val tracks: List<Track>,
        private val directory: CircuitList,
    ) : ImportSink {
        private var extraDefs: List<ChannelDef> = emptyList()
        private var chunks: ChunkWriter? = null
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

        override fun extras(columns: List<ExtraColumn>) {
            if (chunks != null) return // too late: the channel table is written
            val used = (GnssChannels.ALL + Accel).map { it.name }.toMutableSet()
            extraDefs = columns.take(ImportLimits.MaxExtraChannels).mapIndexed { i, c ->
                var name = c.name.replace(Regex("\\s+"), "_").take(NameChars).ifEmpty { "channel_${i + 1}" }
                if (!used.add(name)) name = "import_$name".take(NameChars).also { used.add(it) }
                ChannelDef(FirstExtraId + i, name, c.unit.take(NameChars), Source, ExtraDecimals)
            }
        }

        override fun skipped() {
            skipped++
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
            val w = chunks ?: open(p)
            // Kept in time order: pending already dropped anything not after the point before it.
            val t = p.utcMicros - firstUtc
            if (t <= lastT) {
                skipped++
                return
            }
            lastT = t
            w.write(Record.Samples(SampleSet(t, valuesOf(p))))
            points++
        }

        /** The channel values of [p]; heading is held while standing, speed is 0 where unknown. */
        private fun valuesOf(p: ImportPoint): List<ChannelValue> {
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
                values += ChannelValue(AccelForward.id, p.accelForwardG)
            }
            if (p.accelLateralG.isFinite()) {
                lateralSeen = true
                values += ChannelValue(AccelLateral.id, p.accelLateralG)
            }
            for (i in extraDefs.indices) {
                val v = p.extras.getOrNull(i) ?: continue
                if (v.isFinite()) values += ChannelValue(extraDefs[i].id, v)
            }
            return values
        }

        /** Creates the staged session at the first position, with the track detected there. */
        private fun open(p: ImportPoint): ChunkWriter {
            firstUtc = p.utcMicros
            detection = detectTrack(GeoPoint(p.lat, p.lon), tracks, directory)
            val saved = (detection as? TrackDetection.Saved)?.track
            val channels = GnssChannels.ALL + Accel + extraDefs
            val sessionInfo = SessionInfo(
                startUtcMillis = Math.floorDiv(p.utcMicros, MicrosPerMilliLong),
                track = detection.placeName.orEmpty(),
                devices = listOf("Imported ${origin.format}"),
                startFinish = saved?.startFinishGate(),
                channels = channels,
                imported = origin,
            )
            val (newId, dir) = staged.createSession(sessionInfo)
            id = newId
            info = sessionInfo
            val w = ChunkWriter(dir, ChunkBytes, Long.MAX_VALUE, SyncMs)
            chunks = w
            w.open(channels)
            // As a recording logs it at its first fix, so analysis and sharing find the track (FBBD-55, FBBD-80).
            w.write(Record.Event(RecorderEvent(0, TrackEvent.KIND, TrackEvent.text(detection))))
            return w
        }

        fun finish(format: ForeignFormat): ImportedSession {
            pending?.let { last ->
                pending = null
                emit(if (last.speedKmh.isNaN()) last.copy(speedKmh = 0.0) else last)
            }
            if (points < MinPoints) throw ImportFormatException("the file has no positions with times")
            val w = checkNotNull(chunks)
            val sessionId = checkNotNull(id)
            val sessionInfo = checkNotNull(info)
            val note = "format=${format.label} points=$points skipped=$skipped accel=${forwardSeen || lateralSeen}"
            w.write(Record.Event(RecorderEvent(lastT, ImportedEvent, note)))
            w.write(Record.End(lastT, EndReason))
            w.close()
            chunks = null
            SessionFiles.writeAtomically(
                File(staged.dir(sessionId), SessionFiles.FINISHED),
                Properties().apply {
                    setProperty("reason", EndReason)
                    setProperty("endMicros", lastT.toString())
                    setProperty("droppedSamples", "0")
                },
            )
            return ImportedSession(sessionId, sessionInfo, format, points, skipped, lastT, detection)
        }

        /** Stops the chunk writer's sync thread after a failure; the staged folder is then removed. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun abort() {
            try {
                chunks?.close()
            } catch (_: Exception) {
                // Already failing; the staged folder is removed next.
            }
            chunks = null
        }
    }

    companion object {
        /** Event logged in an imported session: its format and what was read. */
        const val ImportedEvent = "imported"

        /** The end reason of an imported session. */
        const val EndReason = "imported"

        /** Source of the channels an import adds. */
        const val Source = "import"

        /**
         * Accelerometer channels under the RaceBox's names, so analysis (the G-G diagram, braking and
         * exit metrics) reads them as it reads a RaceBox's: X forward, Y sideways, in g.
         */
        val AccelForward = ChannelDef(AccelForwardId, "accel_x", "g", Source, AccelDecimals)
        val AccelLateral = ChannelDef(AccelLateralId, "accel_y", "g", Source, AccelDecimals)
        private val Accel = listOf(AccelForward, AccelLateral)

        private const val AccelForwardId = 110
        private const val AccelLateralId = 111
        private const val AccelDecimals = 3
        private const val MaxLatitude = 90.0
        private const val MaxLongitude = 180.0
        private const val FirstExtraId = 1000
        private const val ExtraDecimals = 3
        private const val NameChars = 64
        private const val MinPoints = 2
        private const val DefaultValues = 9
        private const val ChunkBytes = 8L shl 20
        private const val SyncMs = 500L
        private const val BufferBytes = 64 * 1024
        private const val Gzip0 = 0x1f
        private const val Gzip1 = 0x8b
        private const val MicrosPerSecond = 1_000_000.0
        private const val MicrosPerMilli = 1_000.0
        private const val MicrosPerMilliLong = 1_000L
        private const val KmhPerMs = 3.6
        private const val MinMoveMetres = 0.5
        private const val EarthRadiusMetres = 6_371_008.8

        /** A real position: finite, in range, and not 0°, 0° (a receiver without a fix). */
        private fun valid(p: ImportPoint) = p.lat.isFinite() && p.lon.isFinite() &&
            p.lat in -MaxLatitude..MaxLatitude && p.lon in -MaxLongitude..MaxLongitude &&
            !(p.lat == 0.0 && p.lon == 0.0)

        private fun distance(a: ImportPoint, b: ImportPoint): Double {
            val p1 = Math.toRadians(a.lat)
            val p2 = Math.toRadians(b.lat)
            val dp = p2 - p1
            val dl = Math.toRadians(b.lon - a.lon)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EarthRadiusMetres * atan2(sqrt(h), sqrt(1 - h))
        }

        private fun bearing(a: ImportPoint, b: ImportPoint): Double {
            val p1 = Math.toRadians(a.lat)
            val p2 = Math.toRadians(b.lat)
            val dl = Math.toRadians(b.lon - a.lon)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + FullCircle) % FullCircle
        }

        private const val FullCircle = 360.0
    }
}
