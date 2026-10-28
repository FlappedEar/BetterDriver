package com.flappedear.betterdriver.video

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The text of a session's clip index, `<session>/video/clips.json`: the format Android's VideoIndex
 * and the iOS GoPro import both write, so a session's clips read the same on either.
 */
object ClipIndexJson {
    const val FormatVersion = 1

    /** [clips] in the order the index keeps them: by kind, then start, then file name. */
    fun encode(clips: List<VideoClip>): String =
        json.encodeToString(IndexFile.serializer(), IndexFile(FormatVersion, sorted(clips)))

    /**
     * The clips in [text]. Throws [SerializationException] or [IllegalArgumentException] for text that
     * is not an index this build can read, a newer version included.
     */
    fun decode(text: String): List<VideoClip> {
        val index = json.decodeFromString(IndexFile.serializer(), text)
        if (index.version > FormatVersion) throw SerializationException("newer index version ${index.version}")
        return index.clips
    }

    /** [existing] with [added], each replacing any clip with the same file name. */
    fun merge(existing: List<VideoClip>, added: List<VideoClip>): List<VideoClip> {
        val names = added.map { it.fileName }.toSet()
        return existing.filterNot { it.fileName in names } + added
    }

    fun sorted(clips: List<VideoClip>): List<VideoClip> =
        clips.sortedWith(compareBy({ it.kind }, { it.sessionOffsetUs }, { it.fileName }))

    @Serializable
    private data class IndexFile(val version: Int, val clips: List<VideoClip>)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
