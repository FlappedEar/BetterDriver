package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.Provenance
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
    const val META = "meta.properties"
    const val LOCK = "session.lock"
    const val FINISHED = "finished.properties"
    const val RECOVERY = "recovery.properties"
    const val CHUNK_PREFIX = "chunk-"
    const val CHUNK_SUFFIX = ".bdr"

    fun chunkName(index: Int) = "%s%06d%s".format(CHUNK_PREFIX, index, CHUNK_SUFFIX)

    fun chunks(dir: File): List<File> =
        (dir.listFiles { f -> f.name.startsWith(CHUNK_PREFIX) && f.name.endsWith(CHUNK_SUFFIX) } ?: emptyArray())
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

    fun metaToProps(id: String, info: SessionInfo): Properties = Properties().apply {
        setProperty("format", "1")
        setProperty("id", id)
        setProperty("startUtcMillis", info.startUtcMillis.toString())
        setProperty("car", info.car)
        setProperty("track", info.track)
        setProperty("devices", info.devices.joinToString("\n"))
        info.startFinish?.let { g ->
            setProperty("startFinish.name", g.name)
            setProperty("startFinish.lat1", g.lat1.toString())
            setProperty("startFinish.lon1", g.lon1.toString())
            setProperty("startFinish.lat2", g.lat2.toString())
            setProperty("startFinish.lon2", g.lon2.toString())
        }
        info.imported?.let { o ->
            setProperty("imported.format", o.format)
            setProperty("imported.file", o.fileName)
            if (o.receiver.isNotEmpty()) setProperty("imported.receiver", o.receiver)
            o.sampleRateHz?.let { setProperty("imported.sampleRateHz", it.toString()) }
        }
        setProperty("channels", info.channels.size.toString())
        info.channels.forEachIndexed { i, c ->
            setProperty("channel.$i.id", c.id.toString())
            setProperty("channel.$i.name", c.name)
            setProperty("channel.$i.unit", c.unit)
            setProperty("channel.$i.source", c.source)
            setProperty("channel.$i.decimals", c.decimals.toString())
            // Added by FBBD-174; older builds ignore it.
            setProperty("channel.$i.provenance", c.provenance.name)
        }
    }

    fun propsToMeta(p: Properties): SessionInfo {
        val gate = p.getProperty("startFinish.name")?.let {
            Gate(
                it,
                p.getProperty("startFinish.lat1").toDouble(),
                p.getProperty("startFinish.lon1").toDouble(),
                p.getProperty("startFinish.lat2").toDouble(),
                p.getProperty("startFinish.lon2").toDouble(),
            )
        }
        val devices = p.getProperty("devices", "").split("\n").filter { it.isNotEmpty() }
        val imported = p.getProperty("imported.format")?.let {
            ImportOrigin(
                it,
                p.getProperty("imported.file", ""),
                p.getProperty("imported.receiver", ""),
                p.getProperty("imported.sampleRateHz")?.toDoubleOrNull(),
            )
        }
        val n = p.getProperty("channels", "0").toInt()
        val channels = (0 until n).map { i ->
            val def = ChannelDef(
                p.getProperty("channel.$i.id").toInt(),
                p.getProperty("channel.$i.name"),
                p.getProperty("channel.$i.unit"),
                p.getProperty("channel.$i.source"),
                p.getProperty("channel.$i.decimals").toInt(),
            )
            // A session stored before FBBD-174 has none: derived from what its metadata says.
            val provenance = Provenance.parse(p.getProperty("channel.$i.provenance"))
                ?: Provenance.forSession(def, devices, imported)
            def.copy(provenance = provenance)
        }
        return SessionInfo(
            startUtcMillis = p.getProperty("startUtcMillis").toLong(),
            car = p.getProperty("car", ""),
            track = p.getProperty("track", ""),
            devices = devices,
            startFinish = gate,
            channels = channels,
            imported = imported,
        )
    }

}
