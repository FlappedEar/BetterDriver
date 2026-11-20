package com.flappedear.betterdriver.gopro

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Review v1.34.1 F18: a download that fails part way leaves no `.part` file and keeps a complete file. */
class GoProHttpTest {
    private class Connection(url: URL, private val body: () -> InputStream) : HttpURLConnection(url) {
        override fun getResponseCode() = HTTP_OK
        override fun getInputStream(): InputStream = body()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    /** Serves [bytes], then throws as a dropped Wi-Fi does. */
    private class Cut(private val bytes: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else throw IOException("reset")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) throw IOException("connection reset")
            val n = minOf(len, bytes.size - pos)
            bytes.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }

    private val file = MediaFile("100GOPRO", "GX010001.MP4", 0, 4L * 1024 * 1024)

    @Test
    fun `a download cut mid copy leaves no part file`() {
        val dir = Files.createTempDirectory("gopro").toFile()
        val target = File(dir, file.name)
        val http = GoProHttp(open = { Connection(it) { Cut(ByteArray(1024 * 1024)) } })
        assertFailsWith<IOException> { http.download(file, target) }
        assertFalse(File(dir, file.name + ".part").exists(), "the partial file is removed")
        assertFalse(target.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `a failed download keeps the complete file already there`() {
        val dir = Files.createTempDirectory("gopro").toFile()
        val target = File(dir, file.name)
        val complete = ByteArray(16) { it.toByte() }
        target.writeBytes(complete)
        val http = GoProHttp(open = { Connection(it) { Cut(ByteArray(1000)) } })
        assertFailsWith<IOException> { http.download(file, target) }
        assertContentEquals(complete, target.readBytes())
        assertFalse(File(dir, file.name + ".part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `a failed rename leaves no part file`() {
        val dir = Files.createTempDirectory("gopro").toFile()
        // A directory in the target's place cannot be replaced by renaming a file onto it.
        val target = File(dir, file.name).apply { mkdirs(); File(this, "x").writeText("x") }
        val small = MediaFile("100GOPRO", "GX010001.MP4", 0, 8)
        val http = GoProHttp(open = { Connection(it) { ByteArray(8).inputStream() } })
        assertFailsWith<IOException> { http.download(small, target) }
        assertFalse(File(dir, small.name + ".part").exists())
        dir.deleteRecursively()
    }
}
