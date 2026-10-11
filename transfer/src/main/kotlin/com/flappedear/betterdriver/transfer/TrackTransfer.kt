package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
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

    fun match(incoming: Track, here: Track?): TrackMatch = when {
        here == null -> TrackMatch.NEW
        sameContent(incoming, here) -> TrackMatch.SAME
        else -> TrackMatch.DIFFERENT
    }

    /** Revision and update time are left out: a track copied back and forth is still the same track. */
    private fun sameContent(a: Track, b: Track) =
        a.copy(revision = 1, updatedUtcMillis = 0) == b.copy(revision = 1, updatedUtcMillis = 0)

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
enum class IncomingKind { SESSION, TRACK, UNKNOWN }

object IncomingFiles {
    /** Bytes [kindOf] needs from the start of the file. */
    val PeekBytes = BundleFile.Magic.size

    /** The first [PeekBytes] bytes of [input] (fewer if it is shorter), for [kindOf]. */
    fun head(input: InputStream): ByteArray = readUpTo(input, PeekBytes)

    fun kindOf(head: ByteArray): IncomingKind = when {
        head.size >= BundleFile.Magic.size && head.copyOf(BundleFile.Magic.size).contentEquals(BundleFile.Magic) ->
            IncomingKind.SESSION
        head.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() == '{' -> IncomingKind.TRACK
        else -> IncomingKind.UNKNOWN
    }
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
