package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.core.ble.DiagRow
import com.flappedear.betterdriver.video.IoProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A GoPro's live status for its Equipment page (FBBD-440), against a fake camera. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoProLiveTest {
    /** Answers Get Hardware Info, Get Status Values and Keep Alive; refuses a status query listing [refused]. */
    private class Camera : GoProLink {
        val ids = mutableListOf<Int>()
        var reachable = true
        var refused: Int? = null
        var disconnects = 0
        val statuses = mutableMapOf<Int, ByteArray>()
        override val notifications = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
        private val assemblers = mutableMapOf<String, OpenGoPro.Assembler>()

        override suspend fun connect() {
            if (!reachable) throw IoProblem("not found", null)
        }

        override suspend fun write(characteristic: String, packet: ByteArray) {
            val message = assemblers.getOrPut(characteristic) { OpenGoPro.Assembler() }.accept(packet) ?: return
            val id = message[0].toInt() and 0xFF
            ids += id
            val reply = when (id) {
                0x3C -> byteArrayOf(0x3C, 0) + hardwareInfo()
                0x13 -> {
                    val asked = message.drop(1).map { it.toInt() and 0xFF }
                    if (refused in asked) {
                        byteArrayOf(0x13, 2)
                    } else {
                        byteArrayOf(0x13, 0) + asked.filter { it in statuses }.flatMap { s ->
                            val v = statuses.getValue(s)
                            listOf(s.toByte(), v.size.toByte()) + v.toList()
                        }
                    }
                }
                else -> byteArrayOf(message[0], 0)
            }
            for (p in OpenGoPro.packetize(reply)) notifications.emit(GoProUuids.responseFor(characteristic) to p)
        }

        override suspend fun read(characteristic: String): ByteArray = ByteArray(0)

        override suspend fun disconnect() {
            disconnects++
        }

        private fun hardwareInfo(): ByteArray {
            fun field(b: ByteArray) = byteArrayOf(b.size.toByte()) + b
            return field(byteArrayOf(0, 0, 0, 62)) + field("HERO11 Black".encodeToByteArray()) +
                field(byteArrayOf(0, 0, 0, 4)) + field("H22.01.02.32.00".encodeToByteArray()) +
                field("C3471325208909".encodeToByteArray()) + field("GP25208909".encodeToByteArray()) +
                field("2674f7f65f78".encodeToByteArray())
        }
    }

    @Test
    fun readsInfoAndStatusesAndKeepsTheCameraAwake() = runTest {
        val cam = Camera().apply {
            statuses[GoProLive.INTERNAL_BATTERY_PERCENTAGE] = byteArrayOf(87)
            statuses[GoProLive.INTERNAL_BATTERY_BARS] = byteArrayOf(3)
            statuses[GoProLive.ENCODING] = byteArrayOf(1)
            statuses[GoProLive.VIDEO_ENCODING_DURATION] = byteArrayOf(0, 0, 0, 75)
            statuses[GoProLive.PRIMARY_STORAGE] = byteArrayOf(0)
            statuses[GoProLive.REMAINING_VIDEO_TIME] = byteArrayOf(0, 0, 0x14, 0x50) // 5200 s
            // 30,000,000 kB.
            statuses[GoProLive.SD_CARD_REMAINING] =
                byteArrayOf(0, 0, 0, 0, 0x01, 0xC9.toByte(), 0xC3.toByte(), 0x80.toByte())
        }
        val live = GoProLive(cam, backgroundScope)
        live.start()
        advanceTimeBy(3_500)
        runCurrent()
        val s = live.snapshot()
        assertTrue(s.connected)
        assertTrue(s.polls >= 3)
        // Hardware info once, then status queries, and Keep Alive; never a shutter, setting or Wi-Fi command.
        assertEquals(0x3C, cam.ids.first())
        assertTrue(0x5B in cam.ids)
        assertTrue(cam.ids.all { it in setOf(0x3C, 0x13, 0x5B) })
        val header = GoProLiveText.header("GoPro 8909", s)
        assertTrue(header.ok)
        assertEquals("Connected · Recording", header.title)
        assertEquals("GoPro 8909 · HERO11 Black", header.detail)
        val sections = GoProLiveText.sections(s).associateBy { it.title }
        assertEquals(
            listOf(
                DiagRow("Model", "HERO11 Black"),
                DiagRow("Firmware", "H22.01.02.32.00"),
                DiagRow("Serial number", "C3471325208909"),
            ),
            sections.getValue("Camera").rows,
        )
        assertEquals(listOf(DiagRow("Charge", "87 %"), DiagRow("Bars", "3 of 3")), sections.getValue("Battery").rows)
        assertEquals(
            listOf(DiagRow("Card", "OK"), DiagRow("Video left", "1 h 26 min"), DiagRow("Free space", "30.0 GB")),
            sections.getValue("SD card").rows,
        )
        assertEquals(DiagRow("Recording", "Yes · 1:15"), sections.getValue("Camera state").rows.first())
        live.stop()
        runCurrent()
        assertEquals(1, cam.disconnects)
    }

    @Test
    fun anOlderCameraGetsTheBasicStatuses() = runTest {
        val cam = Camera().apply {
            refused = GoProLive.COLD
            statuses[GoProLive.INTERNAL_BATTERY_BARS] = byteArrayOf(4)
        }
        val live = GoProLive(cam, backgroundScope)
        live.start()
        advanceTimeBy(2_500)
        runCurrent()
        val s = live.snapshot()
        assertTrue(s.connected)
        val battery = GoProLiveText.sections(s).first { it.title == "Battery" }
        assertEquals(DiagRow("Bars", "Charging"), battery.rows.single())
        live.stop()
    }

    @Test
    fun aCameraNotFoundIsTriedAgain() = runTest {
        val cam = Camera().apply { reachable = false }
        val live = GoProLive(cam, backgroundScope, retryMillis = 1_000)
        live.start()
        runCurrent()
        val s = live.snapshot()
        assertFalse(s.connected)
        assertEquals("Trying again", GoProLiveText.header("GoPro", s).title)
        cam.reachable = true
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(live.snapshot().connected)
        live.stop()
    }

    @Test
    fun statusNumbers() {
        assertEquals(-1L, GoProLive.number(byteArrayOf(-1)))
        assertEquals(5200L, GoProLive.number(byteArrayOf(0, 0, 0x14, 0x50)))
        assertEquals(0L, GoProLive.number(ByteArray(0)))
    }
}
