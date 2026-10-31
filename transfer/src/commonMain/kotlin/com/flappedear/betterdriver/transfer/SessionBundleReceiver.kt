package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.metaFromEntries
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson

/** Where [SessionBundleReceiver] puts a session's files: a staging folder of the platform. */
interface StagingFiles {
    /** Starts the file at [path], relative to the staged session folder (parents created). */
    fun create(path: String)

    fun write(bytes: ByteArray, from: Int, to: Int)

    /** Ends the current file, synced to disk. */
    fun close()

    /** Bytes still free where the files go. */
    fun freeBytes(): Long
}

/** A session read from a bundle, staged and checked, not yet in the session list. */
data class ReceivedSession(val sessionId: String, val info: SessionInfo, val track: Track?, val trackJson: String?)

/**
 * The session layer of a bundle (see SessionBundle on the JVM), in common code for the iOS import
 * (FBBD-331): bundle.properties first, then track.json (if any), then session/... files. Session
 * files go to [files]; each file's declared size is checked against [SessionEntryLimits] and the
 * free space before a byte of it is written. Feed a [BundleUnpacker] with this as its sink, then
 * call [result].
 */
class SessionBundleReceiver(private val files: StagingFiles) : BundleEntrySink {
    private enum class Into { HEADER, TRACK, SESSION, META, SKIP }

    private var into = Into.SKIP
    private var small: ByteArray? = null
    private var smallSize = 0
    private var header: Map<String, String>? = null
    private var trackJson: String? = null
    private var meta: ByteArray? = null
    private var first = true

    override fun begin(path: String, size: Long) {
        if (first) {
            first = false
            if (path != Header || size > MaxSmallEntry) throw BundleDamagedException("not a session bundle")
            return startSmall(Into.HEADER, size)
        }
        when {
            path == TrackEntry -> {
                if (size > MaxSmallEntry) throw BundleDamagedException("track too large")
                startSmall(Into.TRACK, size)
            }
            path.startsWith(SessionPrefix) -> {
                val rel = path.removePrefix(SessionPrefix)
                SessionEntryLimits.maxBytes(rel)?.let { max ->
                    if (size > max) throw BundleDamagedException("$rel is too large ($size bytes, at most $max)")
                }
                if (size > files.freeBytes() - SessionEntryLimits.SpaceMarginBytes) {
                    throw NotEnoughSpaceException(size)
                }
                files.create(rel)
                if (rel == SessionFileNames.META) startSmall(Into.META, size) else into = Into.SESSION
            }
            else -> into = Into.SKIP // Entries a newer app adds are skipped.
        }
    }

    private fun startSmall(kind: Into, size: Long) {
        into = kind
        small = ByteArray(size.toInt())
        smallSize = 0
    }

    override fun bytes(bytes: ByteArray, from: Int, to: Int) {
        when (into) {
            Into.SESSION -> files.write(bytes, from, to)
            Into.META -> {
                files.write(bytes, from, to)
                keep(bytes, from, to)
            }
            Into.HEADER, Into.TRACK -> keep(bytes, from, to)
            Into.SKIP -> Unit
        }
    }

    private fun keep(bytes: ByteArray, from: Int, to: Int) {
        bytes.copyInto(small!!, smallSize, from, to)
        smallSize += to - from
    }

    override fun end() {
        when (into) {
            Into.HEADER -> header = parseProperties(small!!)
            Into.TRACK -> trackJson = small!!.decodeToString()
            Into.META -> {
                files.close()
                meta = small
            }
            Into.SESSION -> files.close()
            Into.SKIP -> Unit
        }
        small = null
        into = Into.SKIP
    }

    /** The checked session, once the whole bundle was read. */
    @Suppress("ThrowsCount") // One reason per way a bundle can be bad.
    fun result(): ReceivedSession {
        val h = header ?: throw BundleDamagedException("empty bundle")
        if (h["format"] != Format) throw BundleDamagedException("not a session bundle")
        val id = h["session"].orEmpty()
        if (!SessionEntryLimits.validSessionId(id)) throw BundleDamagedException("bad session id")
        val info = readInfo(meta ?: throw BundleDamagedException("session has no metadata"))
        val track = trackJson?.let { decodeTrack(it) }
        if (track != null && track.id != h["track"]) throw BundleDamagedException("track does not match")
        return ReceivedSession(id, info, track, trackJson)
    }

    private fun readInfo(bytes: ByteArray): SessionInfo = checkedSessionInfo(bytes, "session")

    private fun decodeTrack(json: String): Track = try {
        TrackJson.decode(json)
    } catch (e: TrackFormatException) {
        throw BundleDamagedException("bad track: ${e.message}", e)
    }

    companion object {
        const val Header = "bundle.properties"
        const val TrackEntry = "track.json"
        const val SessionPrefix = "session/"
        const val Format = "betterdriver-session"
        private const val MaxSmallEntry = 4L shl 20
    }
}

/** Far more channels than any receiver records; a larger count is a damaged file. */
private const val MaxChannels = 1024

/**
 * The session info in a session's meta.properties [bytes], checked the same way for a bundle and a
 * backup (as SessionLimits.readInfo on the JVM): its channel count before the channel list is built.
 * [label] names the session in the error.
 */
// Damaged metadata fails in many ways, each with its reason.
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun checkedSessionInfo(bytes: ByteArray, label: String): SessionInfo {
    val props = try {
        parseProperties(bytes)
    } catch (e: IllegalArgumentException) {
        throw BundleDamagedException("$label has bad metadata: ${e.message}", e)
    }
    val channels = (props["channels"] ?: "0").toIntOrNull()
    if (channels == null || channels !in 0..MaxChannels) {
        throw BundleDamagedException("$label has a bad channel count")
    }
    return try {
        metaFromEntries(props)
    } catch (e: RuntimeException) {
        throw BundleDamagedException("$label has bad metadata: ${e.message}", e)
    }
}

/** The device has too little free space for the next file of [bytes] bytes. */
class NotEnoughSpaceException(val bytes: Long) : RuntimeException("not enough free space for $bytes more bytes")
