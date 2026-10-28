@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.transfer.SessionBundleReceiver.Companion.Format
import com.flappedear.betterdriver.transfer.SessionBundleReceiver.Companion.Header
import com.flappedear.betterdriver.transfer.SessionBundleReceiver.Companion.SessionPrefix
import com.flappedear.betterdriver.transfer.SessionBundleReceiver.Companion.TrackEntry
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.synchronizeFile
import platform.Foundation.writeData

/** File name extension of a session bundle, as on Android. */
const val SessionBundleFileExtension = "bdsession"

/**
 * Writes a session as one .bdsession file to share (FBBD-331), the bundle Android's SessionBundle
 * writes: bundle.properties, the track it was timed with as track.json, then every file of the
 * session folder, video included, so Android or another iPhone imports it with its laps. Files
 * are made under [outRoot], from where the app hands them to the share sheet.
 */
class SessionOutbox(private val sessionsRoot: String, private val outRoot: String) {
    private val fm get() = NSFileManager.defaultManager
    private val store = SessionStore(sessionsRoot)

    /**
     * Writes session [id] with [track] to [outRoot]/BetterDriver-<id>.bdsession and returns its
     * path. [onProgress] gets the session bytes written so far and their total. A session still
     * recording, or without metadata, is refused.
     */
    @Throws(Exception::class)
    fun write(id: String, track: Track?, onProgress: (written: Long, total: Long) -> Unit = { _, _ -> }): String {
        val dir = store.dir(id)
        val files = sessionFiles(id, dir)
        mkdirs(outRoot)
        val path = "$outRoot/BetterDriver-$id.$SessionBundleFileExtension"
        val part = "$path.part"
        pack(part, id, track, dir, files, onProgress)
        fm.removeItemAtPath(path, null)
        if (!fm.moveItemAtPath(part, path, null)) throw ImportFileException("could not prepare $path")
        return path
    }

    /** The files of session [id] in [dir] that go into its bundle; refuses one that cannot be sent. */
    @Suppress("ThrowsCount") // One reason per session that cannot be sent.
    private fun sessionFiles(id: String, dir: String): List<String> {
        if (!SessionEntryLimits.validSessionId(id)) throw ImportFileException("bad session id $id")
        if (store.state(dir) == SessionState.RECORDING) throw ImportFileException("$id is still recording")
        val files = contents(dir)
        if (SessionFileNames.META !in files) throw ImportFileException("$id has no metadata")
        return files
    }

    /** Writes the bundle to [part]; on any failure the part written is removed and the error passed on. */
    @Suppress("TooGenericExceptionCaught", "LongParameterList", "ThrowsCount")
    private fun pack(
        part: String,
        id: String,
        track: Track?,
        dir: String,
        files: List<String>,
        onProgress: (Long, Long) -> Unit,
    ) {
        val total = files.sumOf { sizeOf("$dir/$it") }
        fm.removeItemAtPath(part, null)
        if (!fm.createFileAtPath(part, null, null)) throw ImportFileException("cannot create $part")
        val out = NSFileHandle.fileHandleForWritingAtPath(part) ?: throw ImportFileException("cannot open $part")
        try {
            val packer = BundlePacker({ b, from, to -> if (to > from) out.writeData(b.toNSData(from, to)) })
            val header = linkedMapOf("format" to Format, "version" to BundleFormat.Version.toString(), "session" to id)
            track?.let { header["track"] = it.id }
            packer.file(Header, writeProperties(header))
            track?.let { packer.file(TrackEntry, TrackJson.encode(it).encodeToByteArray()) }
            var written = 0L
            for (name in files) {
                val file = "$dir/$name"
                packer.begin(SessionPrefix + name, sizeOf(file))
                pump(file) { bytes, n ->
                    packer.bytes(bytes, 0, n)
                    written += n
                    onProgress(written, total)
                }
                packer.end()
            }
            packer.finish()
            out.synchronizeFile()
            out.closeFile()
        } catch (e: Exception) {
            out.closeFile()
            fm.removeItemAtPath(part, null)
            throw e
        }
    }

    /** The session folder's files that travel, by path in the folder, in order. */
    private fun contents(dir: String): List<String> =
        fm.subpathsOfDirectoryAtPath(dir, null).orEmpty().filterIsInstance<String>()
            .filter { BundleFormat.travels(it.substringAfterLast('/')) && isFile("$dir/$it") }
            .sorted()

    private fun isFile(path: String): Boolean =
        fm.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeRegular
}
