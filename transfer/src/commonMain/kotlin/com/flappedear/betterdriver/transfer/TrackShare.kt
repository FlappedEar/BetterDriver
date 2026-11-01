package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson

/** File name extension of a track file. Its content is what [TrackJson] writes. */
const val TrackFileExtension = "bdtrack"

/** What a file brought in turns out to be, from its first bytes. */
enum class IncomingKind { SESSION, TRACK, BACKUP, UNKNOWN }

/**
 * A track moving between devices as one file (FBBD-80), the same on Android and iOS (FBBD-372): its
 * name, how it is read, and what a file brought in is.
 */
object TrackShare {
    /** The largest track file read. */
    const val MaxTrackBytes = 1 shl 20

    /** Bytes [kindOf] needs from the start of a file. */
    val PeekBytes = BundleFormat.SessionMagic.size

    private const val MaxFileNameChars = 60

    /** File name offered when sending [track]: its name in plain characters, then the extension. */
    fun fileName(track: Track): String {
        val plain = track.displayName.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(MaxFileNameChars)
        return (plain.ifEmpty { "track" }) + "." + TrackFileExtension
    }

    /** The bytes of the track file for [track]. */
    fun encode(track: Track): ByteArray = TrackJson.encode(track).encodeToByteArray()

    /** Reads a track file's [bytes]; throws [BundleDamagedException] for anything else. */
    fun decode(bytes: ByteArray): Track {
        if (bytes.size > MaxTrackBytes) throw BundleDamagedException("file too large for a track")
        return try {
            TrackJson.decode(bytes.decodeToString())
        } catch (e: TrackFormatException) {
            throw BundleDamagedException("not a track file: ${e.message}", e)
        }
    }

    /** What a file is, from its first [PeekBytes] bytes [head]. */
    fun kindOf(head: ByteArray): IncomingKind = when {
        startsWith(head, BundleFormat.SessionMagic) -> IncomingKind.SESSION
        startsWith(head, BundleFormat.BackupMagic) -> IncomingKind.BACKUP
        head.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() == '{' -> IncomingKind.TRACK
        else -> IncomingKind.UNKNOWN
    }

    private fun startsWith(head: ByteArray, magic: ByteArray) =
        head.size >= magic.size && head.copyOf(magic.size).contentEquals(magic)
}
