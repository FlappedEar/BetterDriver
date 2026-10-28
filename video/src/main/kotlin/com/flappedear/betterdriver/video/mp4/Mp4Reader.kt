package com.flappedear.betterdriver.video.mp4

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A java.io [File] as an [Mp4Source]. */
class FileMp4Source(file: File) : Mp4Source {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    override fun read(offset: Long, size: Int): ByteArray = ByteArray(size).also {
        raf.seek(offset)
        raf.readFully(it)
    }

    override fun close() = raf.close()
}

/** [Mp4Parser] on java.io files, the Android app's way in. Damaged files throw [Mp4FormatException]. */
object Mp4Reader {
    fun read(file: File): Mp4Info = FileMp4Source(file).use { Mp4Parser.read(it, file.name) }

    /** Reads [sample]'s bytes; a sample that is too large or lies outside the file is refused before any allocation. */
    fun readSample(file: File, sample: Mp4Sample): ByteArray =
        FileMp4Source(file).use { Mp4Parser.readSample(it, sample) }

    /** Whether [file] is a finished MP4 (see [Mp4Parser.isComplete]); false for a file that cannot be opened. */
    fun isComplete(file: File): Boolean = try {
        FileMp4Source(file).use { Mp4Parser.isComplete(it) }
    } catch (_: IOException) {
        false
    }
}
