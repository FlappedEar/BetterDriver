package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID

/** File name extension of a session bundle. */
const val SessionBundleExtension = "bdsession"

/**
 * A whole session as one file (FBBD-23): its data, its video with the clip index, its GoPro records,
 * and the track it was timed with (FBBD-80). Entries, in order:
 *
 * ```
 * bundle.properties   format, version, session id, track id (if any)
 * track.json          the track, as TrackJson writes it (if any)
 * session/...         every file of the session directory, video included
 * ```
 *
 * Lock files, temporary files and files set aside as unreadable stay behind.
 */
object SessionBundle {
    internal const val Header = "bundle.properties"
    internal const val TrackEntry = "track.json"
    internal const val SessionPrefix = "session/"
    internal const val Format = "betterdriver-session"
    private const val MaxSmallEntry = 4L shl 20

    /** The session's files and their total size: what [write] sends. */
    fun contents(sessionDir: File): List<Pair<String, File>> =
        sessionDir.walkTopDown()
            .filter { it.isFile && travels(it.name) }
            .map { it.relativeTo(sessionDir).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()

    /** Bytes [write] will send for the session's files, for progress. */
    fun size(sessionDir: File): Long = contents(sessionDir).sumOf { it.second.length() }

    /**
     * Writes the session in [sessionDir] with [track] (the one it was timed with, if any) to [out].
     * [onBytes] is told about each block of file bytes written. Refuses a session still recording.
     */
    fun write(store: SessionStore, sessionDir: File, track: Track?, out: OutputStream, onBytes: (Int) -> Unit = {}) {
        if (store.state(sessionDir) == SessionState.RECORDING) throw IOException("the session is still recording")
        val files = contents(sessionDir)
        if (files.none { it.first == SessionFiles.META }) throw IOException("${sessionDir.name} has no metadata")
        val writer = BundleWriter(out)
        val header = Properties().apply {
            setProperty("format", Format)
            setProperty("version", BundleFile.Version.toString())
            setProperty("session", sessionDir.name)
            track?.let { setProperty("track", it.id) }
        }
        val headerBytes = java.io.ByteArrayOutputStream().also { header.store(it, null) }.toByteArray()
        writer.file(Header, headerBytes.size.toLong(), headerBytes.inputStream())
        track?.let {
            val bytes = TrackJson.encode(it).toByteArray(Charsets.UTF_8)
            writer.file(TrackEntry, bytes.size.toLong(), bytes.inputStream())
        }
        for ((path, file) in files) {
            file.inputStream().use { writer.file(SessionPrefix + path, file.length(), it, onBytes) }
        }
        writer.finish()
        out.flush()
    }

    /** A session in a bundle, unpacked into a staging folder and checked, not yet in the session list. */
    data class Received(
        val sessionId: String,
        val info: SessionInfo,
        val track: Track?,
        internal val staged: File,
    ) {
        internal val sessionDir get() = File(staged, "session")
    }

    /**
     * Unpacks a bundle from [input] into a new folder under [staging] and checks it. Nothing reaches
     * the session list until [SessionInbox.accept]; on any error the folder is removed.
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod") // One reason per way a bundle can be bad.
    internal fun receive(input: InputStream, staging: File, onBytes: (Int) -> Unit = {}): Received {
        val staged = File(staging, "in-" + UUID.randomUUID())
        check(staged.mkdirs()) { "could not create ${staged.path}" }
        try {
            var header: Properties? = null
            var track: Track? = null
            BundleReader(input).read { path, size, contents ->
                when {
                    header == null -> {
                        if (path != Header || size > MaxSmallEntry) throw BundleFormatException("not a session bundle")
                        header = Properties().apply { load(contents) }
                    }
                    path == TrackEntry -> {
                        if (size > MaxSmallEntry) throw BundleFormatException("track too large")
                        track = decodeTrack(contents.readBytes())
                    }
                    path.startsWith(SessionPrefix) -> copyTo(File(staged, path), contents, onBytes)
                    else -> Unit // Entries a newer app adds are skipped.
                }
            }
            val h = header ?: throw BundleFormatException("empty bundle")
            if (h.getProperty("format") != Format) throw BundleFormatException("not a session bundle")
            val id = h.getProperty("session").orEmpty()
            if (!SessionInbox.validId(id)) throw BundleFormatException("bad session id")
            val received = Received(id, readInfo(File(staged, "session")), track, staged)
            val trackId = track?.id
            if (trackId != null && trackId != h.getProperty("track")) {
                throw BundleFormatException("track does not match")
            }
            return received
        } catch (e: IOException) {
            staged.deleteRecursively()
            throw e
        } catch (e: IllegalArgumentException) {
            staged.deleteRecursively()
            throw BundleFormatException("damaged bundle: ${e.message}", e)
        }
    }

    private fun decodeTrack(bytes: ByteArray): Track = try {
        TrackJson.decode(bytes.toString(Charsets.UTF_8))
    } catch (e: TrackFormatException) {
        throw BundleFormatException("bad track: ${e.message}", e)
    }

    @Suppress("TooGenericExceptionCaught") // Damaged metadata fails in many ways (as in SessionStore.list).
    private fun readInfo(sessionDir: File): SessionInfo {
        val props = SessionFiles.readProps(File(sessionDir, SessionFiles.META))
            ?: throw BundleFormatException("no session metadata")
        return try {
            SessionFiles.propsToMeta(props)
        } catch (e: RuntimeException) {
            throw BundleFormatException("bad session metadata", e)
        }
    }

    internal fun copyTo(target: File, contents: InputStream, onBytes: (Int) -> Unit) {
        target.parentFile.mkdirs()
        FileOutputStream(target).use { out ->
            val buf = ByteArray(BundleFile.CopyBuffer)
            while (true) {
                val n = contents.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                onBytes(n)
            }
            out.fd.sync()
        }
    }

    /** Whether a file in a session directory goes into the bundle. */
    internal fun travels(name: String) =
        name != SessionFiles.LOCK && !name.endsWith(".tmp") && !name.endsWith(".part") &&
            !name.endsWith(".lock") && !name.contains(".unreadable-")
}
