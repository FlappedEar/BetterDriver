package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import java.security.MessageDigest

/** Hex characters of a [tracksKey]: plenty to tell sets of tracks apart. */
private const val KeyChars = 16

/**
 * A key for [tracks] as they are now: the same tracks with the same lines, radius and revision give
 * the same key, and any save, edit or delete among them gives another.
 */
fun tracksKey(tracks: List<Track>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (t in tracks.sortedBy { it.id }) {
        digest.update(TrackJson.encode(t).toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(KeyChars)
}
