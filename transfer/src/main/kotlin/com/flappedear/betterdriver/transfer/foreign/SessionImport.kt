package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.ChunkWriter
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.track.Track
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

/**
 * Turns a VBO, GPX or RaceChrono CSV file into an ordinary session in [store] (FBBD-164): the same
 * chunk files, metadata and end marker a recording leaves, with no video, so lap timing, analysis,
 * the review, exports, sharing and backup work on it as on any session. The session's metadata
 * names the source format and file ([SessionInfo.imported]).
 *
 * Every channel's provenance is stored with it (FBBD-174): a VBO file's GNSS channels as a GNSS
 * receiver's, a GPX or RaceChrono file's as a GPS of unknown kind; speed or heading the file lacks
 * anywhere, as calculated; acceleration as calculated (these apps usually work it out from GPS);
 * other columns as unknown. The origin also keeps the file's maker, when it names one, and its rate.
 *
 * The file may be gzip-compressed; its format is decided by its content ([ForeignFormat.detect]).
 * Positions are kept in time order (one at or before the previous time is dropped); speed and
 * heading the file lacks are derived from consecutive positions. The track is detected from the
 * first position as a recording does it ([detectTrack]) and logged as the session's track event,
 * so the session is timed against that saved track; tracks are never created from the file.
 * The session is written by the common [ForeignSessionWriter], as on iOS (FBBD-331).
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
        val writer = ForeignSessionWriter(
            JvmSessionFiles(SessionStore(root)),
            ImportOrigin(format.label, fileName),
            c.tracks,
            c.directory,
        )
        try {
            when (format) {
                ForeignFormat.VBO -> VboReader(c.today.toEpochDay()).read(lines(text), writer)
                ForeignFormat.GPX -> GpxReader.read(text, writer)
                ForeignFormat.RACECHRONO_CSV -> RaceChronoCsvReader.read(lines(text), writer)
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

    private fun lines(input: InputStream): LineReader {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        return LineReader { buf -> reader.read(buf) }
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

    /** The staged session's files, through the recorder's own SessionStore and ChunkWriter. */
    private class JvmSessionFiles(private val staged: SessionStore) : ForeignSessionFiles {
        private var dir: File? = null
        private var chunks: ChunkWriter? = null

        override fun create(info: SessionInfo): String {
            val (id, d) = staged.createSession(info)
            dir = d
            return id
        }

        override fun open(channels: List<ChannelDef>) {
            val w = ChunkWriter(checkNotNull(dir), ChunkBytes, Long.MAX_VALUE, SyncMs)
            chunks = w
            w.open(channels)
        }

        override fun write(record: Record) {
            checkNotNull(chunks).write(record)
        }

        override fun writeMeta(id: String, info: SessionInfo) {
            SessionFiles.writeAtomically(File(staged.dir(id), SessionFiles.META), SessionFiles.metaToProps(id, info))
        }

        override fun close() {
            checkNotNull(chunks).close()
            chunks = null
        }

        override fun writeFinished(id: String, reason: String, endMicros: Long) {
            SessionFiles.writeAtomically(
                File(staged.dir(id), SessionFiles.FINISHED),
                Properties().apply {
                    setProperty("reason", reason)
                    setProperty("endMicros", endMicros.toString())
                    setProperty("droppedSamples", "0")
                },
            )
        }

        /** Stops the chunk writer's sync thread after a failure. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun abort() {
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
        const val ImportedEvent = ForeignImport.ImportedEvent

        /** The end reason of an imported session. */
        const val EndReason = ForeignImport.EndReason

        /** Source of the channels an import adds. */
        const val Source = ForeignImport.Source

        /** As [ForeignImport.AccelForward]: the RaceBox's X axis, forward, in g. */
        val AccelForward: ChannelDef = ForeignImport.AccelForward

        /** As [ForeignImport.AccelLateral]: the RaceBox's Y axis, sideways, in g. */
        val AccelLateral: ChannelDef = ForeignImport.AccelLateral

        private const val ChunkBytes = 8L shl 20
        private const val SyncMs = 500L
        private const val BufferBytes = 64 * 1024
        private const val Gzip0 = 0x1f
        private const val Gzip1 = 0x8b
    }
}
