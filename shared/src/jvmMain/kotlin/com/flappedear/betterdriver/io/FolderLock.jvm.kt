package com.flappedear.betterdriver.io

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** One monitor per folder, shared by every caller in this process. */
private val monitors = ConcurrentHashMap<String, Any>()

/** The folders whose lock this thread holds. */
private val holding: ThreadLocal<MutableSet<String>> = ThreadLocal.withInitial { mutableSetOf() }

actual fun <T> withFolderLock(dir: String, change: () -> T): T {
    val folder = File(dir)
    folder.mkdirs()
    val canonical = folder.canonicalFile
    val held = holding.get()
    if (canonical.path in held) return change()
    val monitor = monitors.computeIfAbsent(canonical.path) { Any() }
    return synchronized(monitor) {
        val lockFile = File(canonical.parentFile, canonical.name + LockSuffix)
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                held += canonical.path
                try {
                    change()
                } finally {
                    held -= canonical.path
                }
            }
        }
    }
}

private const val LockSuffix = ".lock"
