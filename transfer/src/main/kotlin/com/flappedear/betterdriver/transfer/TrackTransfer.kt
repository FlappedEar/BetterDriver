package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import java.io.InputStream

/** File name extension of a track file. Its content is what [TrackJson] writes. */
const val TrackFileExtension = "bdtrack"

/** Tracks moving between the phone and the tablet as single files (FBBD-80). */
object TrackTransfer {
    private const val MaxTrackBytes = 1 shl 20

    fun match(incoming: Track, here: Track?): TrackMatch = trackMatch(incoming, here)

    /**
     * Whether keeping [incoming] in place of [here] should take its corner identity too ([TrackStore.save]
     * with adoptIdentity): it carries the driver's corrections (FBBD-179).
     */
    fun carriesCorrections(incoming: Track): Boolean = carriesCorrectionsOf(incoming)

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
