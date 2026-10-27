package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFiles
import java.io.File
import java.io.IOException

/**
 * What a session that arrives from outside (a session bundle or a backup) may hold, checked from
 * each entry's declared size before a byte of it is copied (audit A08). Files the app later reads
 * whole into memory are bounded: the metadata and marker files, the JSON indexes and the chunks.
 * Video and any other file stay streamed and unbounded, so long videos still travel.
 *
 * Paths are relative to the session folder, as `SessionBundle` and `Backup` give them.
 */
internal object SessionLimits {
    /** meta.properties: one session's info and its channel table. */
    const val MaxMetaBytes = SessionEntryLimits.MaxMetaBytes

    /** Far more channels than any receiver records; a larger count is a damaged file. */
    const val MaxChannels = 1024

    /** A chunk is read whole (SessionStore.read); see [SessionEntryLimits.MaxChunkBytes]. */
    const val MaxChunkBytes = SessionEntryLimits.MaxChunkBytes

    /** Marker properties and JSON indexes (video/clips.json, gopro-recordings.json, quality.json). */
    const val MaxSmallFileBytes = SessionEntryLimits.MaxSmallFileBytes

    /** Kept free on the device while receiving, for the app and the session list. */
    const val SpaceMarginBytes = SessionEntryLimits.SpaceMarginBytes

    /** The largest size allowed for the session file at [path], or null when it is streamed and unbounded. */
    fun maxBytes(path: String): Long? = SessionEntryLimits.maxBytes(path)

    /** Refuses the session file at [path] of [size] bytes when it is over its limit. */
    fun checkEntry(path: String, size: Long) {
        val max = maxBytes(path) ?: return
        if (size > max) throw BundleFormatException("$path is too large ($size bytes, at most $max)")
    }

    /** Refuses an entry of [size] bytes when writing it under [dir] would leave less than the margin free. */
    fun checkSpace(dir: File, size: Long, freeBytes: (File) -> Long) {
        if (size > freeBytes(dir) - SpaceMarginBytes) throw IOException("not enough free space for $size more bytes")
    }

    /**
     * The session info in [sessionDir]'s metadata, checked the same way for a bundle and a backup:
     * its size before it is read, its channel count before the channel list is built.
     */
    // Damaged metadata fails in many ways (as in SessionStore.list).
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun readInfo(sessionDir: File, label: String): SessionInfo {
        val file = File(sessionDir, SessionFiles.META)
        if (file.length() > MaxMetaBytes) throw BundleFormatException("$label metadata is too large")
        val props = SessionFiles.readProps(file) ?: throw BundleFormatException("$label has no metadata")
        val channels = props.getProperty("channels", "0").toIntOrNull()
        if (channels == null || channels !in 0..MaxChannels) {
            throw BundleFormatException("$label has a bad channel count")
        }
        return try {
            SessionFiles.propsToMeta(props)
        } catch (e: RuntimeException) {
            throw BundleFormatException("$label has bad metadata", e)
        }
    }
}
