package com.flappedear.betterdriver.gopro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One file in the camera's media list. [created] is the `cre` field: seconds since 1970 on the
 * camera's clock. Whether that clock reads UTC or local time is not stated in the Open GoPro
 * media list tutorial, so matching allows both (see [GoProMedia.filesFor]).
 */
data class MediaFile(val directory: String, val name: String, val created: Long, val size: Long) {
    /** Path as the HTTP API uses it, e.g. "100GOPRO/GX010042.MP4". */
    val path: String get() = "$directory/$name"

    /** Chapter and recording number from GoPro's file naming, e.g. GX020042.MP4 → (2, 42). */
    val chapterAndNumber: Pair<Int, Int>?
        get() = NamePattern.matchEntire(name.uppercase())
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    private companion object {
        val NamePattern = Regex("G[HX](\\d{2})(\\d{4})\\.MP4")
    }
}

/** The camera's media list and how to pick a session's files from it. */
object GoProMedia {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses `GET /gopro/media/list`: `{"id": …, "media": [{"d": "100GOPRO", "fs": [{"n": …,
     * "cre": …, "mod": …, "s": …}]}]}` (https://gopro.github.io/OpenGoPro/tutorials/camera-media-list).
     * Numbers may come as strings.
     */
    fun parseList(text: String): List<MediaFile> {
        val root = json.parseToJsonElement(text).jsonObject
        val media = root["media"] as? JsonArray ?: return emptyList()
        return media.flatMap { dir ->
            val d = (dir as? JsonObject) ?: return@flatMap emptyList()
            val name = d["d"].string() ?: return@flatMap emptyList()
            (d["fs"] as? JsonArray).orEmpty().mapNotNull { f ->
                val o = f as? JsonObject ?: return@mapNotNull null
                val n = o["n"].string() ?: return@mapNotNull null
                MediaFile(name, n, o["cre"].string()?.toLongOrNull() ?: 0, o["s"].string()?.toLongOrNull() ?: 0)
            }
        }
    }

    /**
     * The video files of [recording]: MP4s whose recording was created between the start and stop
     * acknowledgements, with [slackSeconds] either side. `cre` is compared both as UTC and as local
     * time at the offset the app set on the camera, since its meaning is not documented. All
     * chapters of a matching recording are included, in chapter order.
     */
    fun filesFor(recording: GoProRecording, files: List<MediaFile>, slackSeconds: Long = 30): List<MediaFile> {
        val start = recording.startAckUtcMillis / MillisPerSecond - slackSeconds
        val stop = (recording.stopAckUtcMillis ?: recording.startAckUtcMillis) / MillisPerSecond + slackSeconds
        val offsetSeconds = (recording.cameraUtcOffsetMinutes ?: 0) * SecondsPerMinute
        val videos = files.filter { it.chapterAndNumber != null }
        val firstChapters = videos.filter { f ->
            f.chapterAndNumber!!.first == 1 &&
                (f.created in start..stop || f.created - offsetSeconds in start..stop)
        }
        val numbers = firstChapters.map { it.chapterAndNumber!!.second }.toSet()
        return videos.filter { it.chapterAndNumber!!.second in numbers }
            .sortedWith(compareBy({ it.chapterAndNumber!!.second }, { it.chapterAndNumber!!.first }))
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private const val MillisPerSecond = 1000L
    private const val SecondsPerMinute = 60
}
