package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.store.SessionFileNames

/**
 * The bundle container's constants and checks, shared by the JVM writer and reader (BundleFile)
 * and the iOS import (FBBD-331). See BundleFile for the layout.
 */
object BundleFormat {
    /** "BDBUNDLE" in ASCII: a session bundle. */
    val SessionMagic = "BDBUNDLE".encodeToByteArray()

    /** "BDBACKUP" in ASCII: a backup (FBBD-156), the same container. */
    val BackupMagic = "BDBACKUP".encodeToByteArray()
    const val Version = 1
    const val FileTag = 1
    const val EndTag = 0
    const val MaxPathChars = 512

    /**
     * Whether a file named [name] in a session folder goes into a bundle: lock, temporary and
     * partial files, and files set aside as unreadable, stay behind.
     */
    fun travels(name: String) =
        name != SessionFileNames.LOCK && !name.endsWith(".tmp") && !name.endsWith(".part") &&
            !name.endsWith(".lock") && !name.contains(".unreadable-")

    /** Relative paths of letters, digits, '.', '-' and '_', split by '/', with no empty, '.' or '..' part. */
    fun validPath(path: String) = path.isNotEmpty() && path.length <= MaxPathChars &&
        path.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.all { it.isLetterOrDigit() || it in ".-_" }
        }
}

/**
 * What a session file that arrives from outside may hold (audit A08), by its path in the session
 * folder: files the app reads whole are bounded, video and other files are streamed and unbounded.
 */
object SessionEntryLimits {
    /** meta.properties: one session's info and its channel table. */
    const val MaxMetaBytes = 1L shl 20

    /**
     * A chunk is read whole. The recorder starts a new chunk at 8 MiB (RecorderConfig.chunkMaxBytes)
     * and a frame holds at most 1 MiB (Frames.MAX_PAYLOAD), so a chunk it writes stays under 10 MiB;
     * this leaves room for a larger setting.
     */
    const val MaxChunkBytes = 32L shl 20

    /** Marker properties and JSON indexes (video/clips.json, gopro-recordings.json, quality.json). */
    const val MaxSmallFileBytes = 4L shl 20

    /** Kept free on the device while receiving, for the app and the session list. */
    const val SpaceMarginBytes = 64L shl 20

    /** The largest size allowed for the session file at [path], or null when it is streamed and unbounded. */
    fun maxBytes(path: String): Long? {
        val name = path.substringAfterLast('/')
        return when {
            path == SessionFileNames.META -> MaxMetaBytes
            !path.contains('/') && SessionFileNames.isChunk(name) -> MaxChunkBytes
            name.endsWith(".properties") || name.endsWith(".json") -> MaxSmallFileBytes
            else -> null
        }
    }

    /** Session ids are made by the session store; anything else could escape the session folder. */
    fun validSessionId(id: String) = id.isNotEmpty() && id.length <= MaxIdChars &&
        id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' } &&
        !id.startsWith("-")

    private const val MaxIdChars = 64
}
