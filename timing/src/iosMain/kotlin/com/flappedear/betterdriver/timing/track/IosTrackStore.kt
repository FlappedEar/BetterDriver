package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.withFolderLock

/**
 * The tracks in [dir] on the iPhone and iPad, kept by the TrackStore Android uses (FBBD-359). Builds
 * before it kept them as `<id>.json`; those are renamed once to the store's `<id>.track.json`, so no
 * saved track is lost. A name already taken by the store's file is left as it is.
 */
fun iosTrackStore(dir: String): TrackStore {
    if (CoreFiles.list(dir).any(::isLegacyName)) {
        withFolderLock(dir) {
            CoreFiles.list(dir).filter(::isLegacyName).forEach { name ->
                val renamed = CoreFiles.join(dir, name.removeSuffix(LegacySuffix) + StoreSuffix)
                if (!CoreFiles.exists(renamed)) CoreFiles.move(CoreFiles.join(dir, name), renamed)
            }
        }
    }
    return TrackStore(dir)
}

private fun isLegacyName(name: String) =
    name.endsWith(LegacySuffix) && !name.endsWith(StoreSuffix) && !name.startsWith(".")

private const val LegacySuffix = ".json"
private const val StoreSuffix = ".track.json"
