@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.io

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSRecursiveLock
import platform.posix.EINTR
import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.open

/**
 * One lock for every folder in this process: changes to shared files are rare and short. It is
 * reentrant; [held] counts, per folder, how often the thread holding it took that folder's lock, so
 * only the outermost call takes the file lock (a second flock on another descriptor would wait on
 * the first forever).
 */
private val processLock = NSRecursiveLock()
private val held = mutableMapOf<String, Int>()

actual fun <T> withFolderLock(dir: String, change: () -> T): T {
    val folder = dir.trimEnd('/')
    if (!PosixFiles.mkdirs(folder)) throw FileError("mkdirs $folder")
    processLock.lock()
    try {
        val depth = held[folder] ?: 0
        if (depth > 0) return nested(folder, depth, change)
        val fd = lockFile(folder + LockSuffix)
        try {
            return nested(folder, 0, change)
        } finally {
            flock(fd, LOCK_UN)
            close(fd)
        }
    } finally {
        processLock.unlock()
    }
}

/** Opens [path] and waits for its exclusive lock; returns the descriptor. */
private fun lockFile(path: String): Int {
    val fd = open(path, O_WRONLY or O_CREAT, FileMode)
    if (fd < 0) throw FileError("open $path: errno $errno")
    while (flock(fd, LOCK_EX) != 0) {
        if (errno != EINTR) {
            close(fd)
            throw FileError("flock $path: errno $errno")
        }
    }
    return fd
}

private inline fun <T> nested(folder: String, depth: Int, change: () -> T): T {
    held[folder] = depth + 1
    try {
        return change()
    } finally {
        if (depth == 0) held.remove(folder) else held[folder] = depth
    }
}

private const val LockSuffix = ".lock"
private const val FileMode = 0x1A4 // 0644
