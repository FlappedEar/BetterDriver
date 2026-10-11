package com.flappedear.betterdriver.recorder.video

import com.flappedear.betterdriver.recorder.store.SessionFiles
import java.io.File
import java.util.Properties

/**
 * Clips added after a session, such as GoPro HERO8 files lined up by hand (FBBD-22).
 * Chunk files are never changed once written, so these go in a sidecar file in the session's
 * video directory, replaced atomically on every change.
 *
 * Format: `clip.<n>.camera`, `clip.<n>.file`, `clip.<n>.start_us`, optional `clip.<n>.end_us`.
 */
object ImportedClips {
    const val FILE = "imported.properties"

    fun read(sessionDir: File): List<VideoClip> {
        val p = SessionFiles.readProps(File(File(sessionDir, VideoFiles.DIR), FILE))
        val count = p?.getProperty("count")?.toIntOrNull() ?: 0
        if (p == null) return emptyList()
        return (0 until count).mapNotNull { i ->
            val camera = p.getProperty("clip.$i.camera") ?: return@mapNotNull null
            val file = p.getProperty("clip.$i.file") ?: return@mapNotNull null
            val start = p.getProperty("clip.$i.start_us")?.toLongOrNull() ?: return@mapNotNull null
            VideoClip(camera, file, start, p.getProperty("clip.$i.end_us")?.toLongOrNull())
        }
    }

    fun write(sessionDir: File, clips: List<VideoClip>) {
        val dir = File(sessionDir, VideoFiles.DIR).apply { mkdirs() }
        val p = Properties()
        p.setProperty("count", clips.size.toString())
        clips.forEachIndexed { i, c ->
            p.setProperty("clip.$i.camera", c.camera)
            p.setProperty("clip.$i.file", c.file)
            p.setProperty("clip.$i.start_us", c.startMicros.toString())
            c.endMicros?.let { p.setProperty("clip.$i.end_us", it.toString()) }
        }
        SessionFiles.writeAtomically(File(dir, FILE), p)
    }
}
