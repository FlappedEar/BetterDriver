package com.flappedear.betterdriver.io

/** A file could not be read or written: java.io.IOException on Android, its own type on iOS. */
expect open class IOException(message: String?) : Exception

/**
 * The few file operations the stores of the shared core need (FBBD-359), by path, so that the
 * Android app and the iOS app keep their files the same way: java.io on Android, POSIX calls on
 * iOS. Writes go through a synced temporary file and a rename, so a reader never sees half a file.
 */
@Suppress("TooManyFunctions") // One small call per file operation the stores use.
expect object CoreFiles {
    /** [name] in folder [dir]. */
    fun join(dir: String, name: String): String

    fun isFile(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** Whether anything is at [path]. */
    fun exists(path: String): Boolean

    /** The size of the file at [path] in bytes; 0 when there is none. */
    fun size(path: String): Long

    /** The names of what is in folder [dir]; none when it cannot be listed. */
    fun list(dir: String): List<String>

    /** The UTF-8 text of the file at [path]; throws [IOException] when it cannot be read. */
    fun readText(path: String): String

    /** The bytes of the file at [path]; throws [IOException] when it cannot be read. */
    fun readBytes(path: String): ByteArray

    /** Writes [bytes] to [path], its folders made first, through a synced temporary file and a rename. */
    fun writeBytesAtomically(path: String, bytes: ByteArray)

    /** Renames [from] to [to], replacing it; returns whether it did. */
    fun move(from: String, to: String): Boolean

    /** Writes [text] as UTF-8 to [path], its folders made first, through a synced temporary file and a rename. */
    fun writeTextAtomically(path: String, text: String)

    /** Deletes the file at [path]; returns whether it did. */
    fun delete(path: String): Boolean

    /** Deletes [path] with everything in it; returns whether nothing is left there. */
    fun deleteRecursively(path: String): Boolean
}

/** The files in folder [dir] whose names end with [suffix], by name. */
fun CoreFiles.filesEndingWith(dir: String, suffix: String): List<String> =
    list(dir).filter { it.endsWith(suffix) && isFile(join(dir, it)) }
