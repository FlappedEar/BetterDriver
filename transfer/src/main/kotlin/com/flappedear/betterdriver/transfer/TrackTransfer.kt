package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackRevision
import java.io.InputStream

/** File name extension of a track file. Its content is what [TrackJson] writes. */
const val TrackFileExtension = "bdtrack"

/** How a track brought from the other device compares with the one here (FBBD-80). */
enum class TrackMatch {
    /** No track with that id here: it is added without asking. */
    NEW,

    /** Same name, place and lines: nothing to do. */
    SAME,

    /** Both devices have the track and they differ: the user keeps one or the other. */
    DIFFERENT,
}

/** Tracks moving between the phone and the tablet as single files (FBBD-80). */
object TrackTransfer {
    private const val MaxTrackBytes = 1 shl 20

    /** Corner boundaries this close on the ground are the same place: a moved one is at least metres away. */
    private const val SameBoundaryMeters = 1.0

    fun match(incoming: Track, here: Track?): TrackMatch = when {
        here == null -> TrackMatch.NEW
        sameContent(incoming, here) -> TrackMatch.SAME
        else -> TrackMatch.DIFFERENT
    }

    /**
     * Revision, update time and corner identity (FBBD-172, made from laps on each device) are left
     * out: a track copied back and forth is still the same track. The driver's corner corrections
     * (FBBD-179) count when [incoming] has them: they are the driver's, not made from laps, so a
     * track whose corners were corrected on the other device is not the one here until this one has
     * the same corners, chicanes and names, starting at the same places.
     */
    private fun sameContent(incoming: Track, here: Track) =
        incoming.copy(revision = 1, updatedUtcMillis = 0, identity = null) ==
            here.copy(revision = 1, updatedUtcMillis = 0, identity = null) &&
            (incoming.identity?.corrected != true || sameCorners(incoming.identity, here.identity))

    /**
     * Whether [a] and [b] have the same corners and straights in lap order, as the driver reads them
     * and where each starts on the ground: comparable across devices, whose centrelines and metres
     * along them are each their own. A boundary the driver moved (FBBD-179) makes them differ.
     */
    private fun sameCorners(a: TrackRevision?, b: TrackRevision?): Boolean {
        if (a == null || b == null || a.segments.size != b.segments.size) return a == b
        return a.segments.zip(b.segments).all { (x, y) ->
            x.type == y.type && x.name == y.name &&
                distanceMeters(a.pointAt(x.startMeters), b.pointAt(y.startMeters)) <= SameBoundaryMeters
        }
    }

    /**
     * Whether keeping [incoming] in place of [here] should take its corner identity too ([TrackStore.save]
     * with adoptIdentity): it carries the driver's corrections (FBBD-179).
     */
    fun carriesCorrections(incoming: Track): Boolean = incoming.identity?.corrected == true

    /** File name offered when sending [track]: its name in plain characters, then the extension. */
    fun fileName(track: Track): String {
        val plain = track.displayName.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(MaxFileNameChars)
        return (plain.ifEmpty { "track" }) + "." + TrackFileExtension
    }

    /** Reads a track file; throws [BundleFormatException] for anything else. */
    fun read(input: InputStream): Track {
        val bytes = readUpTo(input, MaxTrackBytes + 1)
        if (bytes.size > MaxTrackBytes) throw BundleFormatException("file too large for a track")
        return try {
            TrackJson.decode(bytes.toString(Charsets.UTF_8))
        } catch (e: TrackFormatException) {
            throw BundleFormatException("not a track file: ${e.message}", e)
        }
    }

    private const val MaxFileNameChars = 60
}

/** What a file brought in turns out to be, from its first bytes. */
enum class IncomingKind { SESSION, TRACK, BACKUP, UNKNOWN }

object IncomingFiles {
    /** Bytes [kindOf] needs from the start of the file. */
    val PeekBytes = BundleFile.Magic.size

    /** The first [PeekBytes] bytes of [input] (fewer if it is shorter), for [kindOf]. */
    fun head(input: InputStream): ByteArray = readUpTo(input, PeekBytes)

    fun kindOf(head: ByteArray): IncomingKind = when {
        startsWith(head, BundleFile.Magic) -> IncomingKind.SESSION
        startsWith(head, Backup.Magic) -> IncomingKind.BACKUP
        head.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() == '{' -> IncomingKind.TRACK
        else -> IncomingKind.UNKNOWN
    }

    private fun startsWith(head: ByteArray, magic: ByteArray) =
        head.size >= magic.size && head.copyOf(magic.size).contentEquals(magic)
}

/** Reads up to [limit] bytes. InputStream.readNBytes needs Android API 33; the app runs from API 26. */
internal fun readUpTo(input: InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(minOf(limit, DEFAULT_BUFFER_SIZE))
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
