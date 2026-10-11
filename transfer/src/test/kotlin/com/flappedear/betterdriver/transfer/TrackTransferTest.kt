package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrackTransferTest {
    @TempDir
    lateinit var tmp: File

    private val poznan = Track(
        id = "tor-poznan",
        name = "Tor Poznań",
        layout = "Full",
        center = GeoPoint(52.4204, 16.8126),
        startFinish = TrackLine(GeoPoint(52.4200, 16.8120), GeoPoint(52.4201, 16.8125)),
        sectorLines = listOf(TrackLine(GeoPoint(52.4210, 16.8130), GeoPoint(52.4211, 16.8135))),
        pitEntry = TrackLine(GeoPoint(52.4190, 16.8110), GeoPoint(52.4191, 16.8112)),
    )

    @Test
    fun `a track file made on one device is read back the same on the other`() {
        val phone = TrackStore(File(tmp, "phone"))
        val saved = phone.save(poznan, nowUtcMillis = 1_000)
        val file = TrackJson.encode(saved).toByteArray()

        val read = TrackTransfer.read(file.inputStream())
        assertEquals(saved, read)
        val tablet = TrackStore(File(tmp, "tablet"))
        assertEquals(TrackMatch.NEW, TrackTransfer.match(read, tablet.get(read.id)))
        tablet.save(read)
        assertEquals(TrackMatch.SAME, TrackTransfer.match(read, tablet.get(read.id)), "revision and time do not count")
    }

    @Test
    fun `a track whose lines differ needs a choice`() {
        val moved = poznan.copy(sectorLines = emptyList())
        assertEquals(TrackMatch.DIFFERENT, TrackTransfer.match(moved, poznan))
        assertEquals(TrackMatch.DIFFERENT, TrackTransfer.match(poznan.copy(name = "Poznań"), poznan))
    }

    @Test
    fun `other files are not read as tracks`() {
        assertFailsWith<BundleFormatException> { TrackTransfer.read("{\"a\":1}".byteInputStream()) }
        assertFailsWith<BundleFormatException> { TrackTransfer.read("hello".byteInputStream()) }
        val huge = ByteArray(2 shl 20) { ' '.code.toByte() }
        assertFailsWith<BundleFormatException> { TrackTransfer.read(huge.inputStream()) }
    }

    @Test
    fun `the first bytes tell a session from a track, also from a stream that gives one byte at a time`() {
        val bundle = BundleFile.Magic + ByteArray(100)
        assertEquals(IncomingKind.SESSION, IncomingFiles.kindOf(IncomingFiles.head(Trickle(bundle))))
        val track = TrackJson.encode(poznan).toByteArray()
        assertEquals(IncomingKind.TRACK, IncomingFiles.kindOf(IncomingFiles.head(Trickle(track))))
        assertEquals(IncomingKind.UNKNOWN, IncomingFiles.kindOf(IncomingFiles.head("BD".byteInputStream())))
        assertEquals(poznan, TrackTransfer.read(Trickle(track)))
    }

    /** Hands out one byte per read, as some content providers do. */
    private class Trickle(bytes: ByteArray) : java.io.InputStream() {
        private val inner = bytes.inputStream()
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, 1))
    }

    @Test
    fun `file names are plain`() {
        assertEquals("Tor-Poznań-Full.bdtrack", TrackTransfer.fileName(poznan))
        assertEquals("track.bdtrack", TrackTransfer.fileName(poznan.copy(name = "///", layout = "")))
    }
}
