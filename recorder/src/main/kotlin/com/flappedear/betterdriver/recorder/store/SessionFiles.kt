package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.SessionInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * Layout of one session directory:
 *
 * ```
 * meta.properties      session info, written atomically before the first sample
 * chunk-000001.bdr     append-only framed records (see Frames), rotated by size and age
 * chunk-000002.bdr     ...
 * session.lock         held by the recording process for as long as it records
 * finished.properties  written atomically after a clean stop
 * recovery.properties  written by recovery for an interrupted session; chunks are never modified
 * ```
 */
object SessionFiles {
    const val META = SessionFileNames.META
    const val LOCK = SessionFileNames.LOCK
    const val FINISHED = SessionFileNames.FINISHED
    const val RECOVERY = SessionFileNames.RECOVERY
    const val CHUNK_PREFIX = SessionFileNames.CHUNK_PREFIX
    const val CHUNK_SUFFIX = SessionFileNames.CHUNK_SUFFIX

    fun chunkName(index: Int) = SessionFileNames.chunkName(index)

    fun chunks(dir: File): List<File> =
        (dir.listFiles { f -> SessionFileNames.isChunk(f.name) } ?: emptyArray())
            .sortedBy { it.name }

    /** Writes [props] to [target] via a synced temp file and a rename, so readers never see half a file. */
    fun writeAtomically(target: File, props: Properties) {
        // store(OutputStream) writes ISO-8859-1 with \uXXXX escapes, so any Unicode text survives.
        val bytes = java.io.ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("could not rename ${tmp.path} to ${target.path}")
        }
        syncDir(target.parentFile)
    }

    /** Best effort: makes a create or rename durable. Not supported on every platform. */
    fun syncDir(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: java.io.IOException) {
            // Directory fsync is not supported everywhere; the data files are synced regardless.
        }
    }

    /** Bytes on disk of the session [dir], its video included. */
    fun sizeBytes(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun readProps(file: File): Properties? {
        if (!file.isFile) return null
        return try {
            Properties().apply { file.inputStream().use { load(it) } }
        } catch (_: java.io.IOException) {
            null // unreadable: treated as missing
        } catch (_: IllegalArgumentException) {
            null // malformed escape in the file
        }
    }

    /** Takes the session lock for this process. Released by [SessionLock.release] or when the process dies. */
    fun lock(dir: File): SessionLock {
        val ch = FileChannel.open(File(dir, LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        return SessionLock(ch, ch.lock())
    }

    class SessionLock internal constructor(
        private val channel: FileChannel,
        private val lock: java.nio.channels.FileLock,
    ) {
        fun release() {
            try {
                lock.release()
                channel.close()
            } catch (_: java.io.IOException) {
                // The lock goes away with the process anyway.
            }
        }
    }

    /** True if some process holds [LOCK], meaning the session is still being recorded. */
    fun isLocked(dir: File): Boolean {
        val lockFile = File(dir, LOCK)
        return lockFile.exists() && try {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE).use { ch ->
                val lock = ch.tryLock()
                lock?.release()
                lock == null
            }
        } catch (_: OverlappingFileLockException) {
            true // held by this JVM
        } catch (_: java.io.IOException) {
            false
        }
    }

    fun metaToProps(id: String, info: SessionInfo): Properties =
        Properties().apply { metaEntries(id, info).forEach { (k, v) -> setProperty(k, v) } }

    fun propsToMeta(p: Properties): SessionInfo =
        metaFromEntries(p.stringPropertyNames().associateWith { p.getProperty(it) })
}
