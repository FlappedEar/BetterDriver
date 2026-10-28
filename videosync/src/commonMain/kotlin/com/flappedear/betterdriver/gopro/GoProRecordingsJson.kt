package com.flappedear.betterdriver.gopro

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The text of `<session>/video/gopro-recordings.json`: the Open GoPro recordings of a session, of
 * any number of cameras, in the format the Android app and the iPhone both write.
 */
object GoProRecordingsJson {
    const val FileName = "gopro-recordings.json"

    /** The recordings in [text]; none for text that is not such a list. */
    fun decode(text: String): List<GoProRecording> = try {
        json.decodeFromString<List<GoProRecording>>(text)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    fun encode(recordings: List<GoProRecording>): String = json.encodeToString(recordings)

    /** [existing] with [recording], replacing the one with the same source and start. */
    fun put(existing: List<GoProRecording>, recording: GoProRecording): List<GoProRecording> =
        existing.filterNot {
            it.sourceId == recording.sourceId && it.startAckSessionUs == recording.startAckSessionUs
        } + recording

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}
