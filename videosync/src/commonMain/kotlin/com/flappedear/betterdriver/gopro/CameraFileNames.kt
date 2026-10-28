package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.video.VideoFiles

/** A GoPro file name, after the camera prefix the import adds ([plainName]): chapter, then recording number. */
private val GoProName = Regex("(?:.*-)?G[HXL](\\d{2})(\\d{4})\\.MP4", RegexOption.IGNORE_CASE)
private val PlainName = Regex("[A-Za-z0-9._-]+")

/**
 * A safe file name for the [index]th file picked for camera [sourceId]: its own name after the
 * camera's id if it is a plain .MP4 name, else a numbered name.
 */
fun plainName(sourceId: String, name: String?, index: Int): String {
    val clean = name?.substringAfterLast('/')?.trim().orEmpty()
    val ok = clean.matches(PlainName) && clean.endsWith(".mp4", ignoreCase = true)
    return if (ok) "$sourceId-$clean" else VideoFiles.liveName(sourceId, index + 1, "MP4")
}

/**
 * Groups GoPro files by recording, chapters in order: GH010031.MP4 and GH020031.MP4 (with or
 * without the camera prefix) are chapters 1 and 2 of recording 0031. Files with other names are
 * each their own recording. [name] is an item's file name.
 */
fun <T> byRecording(items: List<T>, name: (T) -> String): List<List<T>> {
    fun part(item: T, group: Int) = GoProName.matchEntire(name(item))?.groupValues?.get(group).orEmpty()
    val (named, other) = items.partition { GoProName.matches(name(it)) }
    val groups = named.groupBy { part(it, 2) }
        .entries
        .sortedBy { it.key }
        .map { (_, group) -> group.sortedBy { part(it, 1).toIntOrNull() ?: 0 } }
    return groups + other.map { listOf(it) }
}
