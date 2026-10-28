@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.gopro.GoProAck
import com.flappedear.betterdriver.gopro.GoProRecording
import com.flappedear.betterdriver.gopro.GoProUuids
import com.flappedear.betterdriver.gopro.OpenGoPro
import com.flappedear.betterdriver.gopro.OpenGoProCamera
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.VideoSourceState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iPhone's side of Open GoPro start and stop (FBBD-343): the Bluetooth bridge and the setup it reads. */
class OpenGoProsTest {
    /** A camera behind the bridge, answering as the app's CoreBluetooth code would pass it on. */
    private class FakeBle : IosGoProLink() {
        val sent = mutableListOf<Int>()
        var recording = false
        var reachable = true
        private val assembler = OpenGoPro.Assembler()

        override fun beginConnect() = connected(if (reachable) null else "GoPro not found")

        override fun beginWrite(characteristic: String, packet: NSData) {
            written(null)
            val message = assembler.accept(packet.toBytes()) ?: return
            val id = message[0].toInt() and 0xFF
            sent += id
            val reply = when {
                characteristic == GoProUuids.QUERY -> byteArrayOf(0x13, 0, 8, 1, 0)
                id == 0x01 -> byteArrayOf(0x01, 0).also { recording = message[2].toInt() == 1 }
                else -> byteArrayOf(message[0], 0)
            }
            // CoreBluetooth names characteristics in upper case.
            val response = GoProUuids.responseFor(characteristic).uppercase()
            for (p in OpenGoPro.packetize(reply)) notified(response, p.data())
        }

        override fun beginRead(characteristic: String) = readDone("GP1234".encodeToByteArray().data(), null)

        override fun beginDisconnect() = Unit
    }

    @Test
    fun aCameraStartsAndStopsThroughTheBridge() = runBlocking {
        val ble = FakeBle()
        val acks = mutableListOf<GoProAck>()
        val saved = mutableListOf<GoProRecording>()
        var now = 2_000_000L
        val camera = OpenGoProCamera("gopro-a", ble, this, IosPhoneClock, acks = { acks += it })
        camera.start({ now }) { saved += it }
        delay(200)
        assertTrue(ble.recording)
        assertEquals(VideoSourceState.Recording(2_000_000), camera.state.value)
        now = 9_000_000
        camera.stop()
        camera.close()
        assertFalse(ble.recording)
        assertEquals(listOf(0x0F, 0x13, 0x01, 0x01), ble.sent)
        assertEquals(listOf(GoProAck("start", true, 2_000_000), GoProAck("stop", true, 9_000_000)), acks)
        assertEquals(9_000_000, saved.last().stopAckSessionUs)
    }

    @Test
    fun aMissingCameraFailsWithoutThrowing() = runBlocking {
        val ble = FakeBle().apply { reachable = false }
        val camera = OpenGoProCamera("gopro-a", ble, this, IosPhoneClock)
        camera.start({ 0 }) {}
        delay(100)
        assertEquals(VideoSourceState.Failed("GoPro not found"), camera.state.value)
        camera.stop()
        camera.close()
    }

    @Test
    fun aLostLinkFailsWhatWaitsOnIt() = runBlocking {
        val ble = object : IosGoProLink() {
            override fun beginConnect() = connected(null)
            override fun beginWrite(characteristic: String, packet: NSData) = disconnected("link lost")
            override fun beginRead(characteristic: String) = Unit
            override fun beginDisconnect() = Unit
        }
        ble.connect()
        val e = assertFailsWith<IoProblem> { ble.write(GoProUuids.COMMAND, byteArrayOf(1)) }
        assertEquals("link lost", e.message)
        assertEquals("GP1234", FakeBle().read(GoProUuids.WIFI_AP_SSID).decodeToString())
    }

    @Test
    fun theListedGoProsAreTheOpenGoProCamerasWithTheirChoice() {
        var setup = SettingsCore.freshSetup("cam")
        setup = SettingsCore.addOpenGoPro(setup, "GoPro 1234", "ble-uuid", GoProModel.HERO11_BLACK, "gp")
        setup = SettingsCore.addEquipment(setup, SettingsCore.model("GOPRO_FILES")!!, "Old GoPro", null, "files")
        val listed = OpenGoPros.listed(setup)
        assertEquals(listOf("gp"), listed.map { it.id })
        val gp = listed.single()
        assertEquals("gopro-gp", gp.sourceId)
        assertEquals("ble-uuid", gp.address)
        assertTrue(gp.on)
        assertFalse(OpenGoPros.listed(SettingsCore.useCamera(setup, "gp", false)).single().on)
        assertEquals(EquipmentModel.GOPRO, setup.equipment("gp")?.model)
    }

    @Test
    fun videoModesMapToOpenGoProSettings() {
        val v = goProVideo(VideoMode(VideoSize.FHD, 60))
        assertEquals(OpenGoPro.Setting.RES_1080, v?.resolution)
        assertEquals(OpenGoPro.Setting.FPS_60, v?.fps)
        assertEquals(OpenGoPro.Setting.RES_2_7K, goProVideo(VideoMode(VideoSize.QHD, 30))?.resolution)
        assertNull(goProVideo(VideoMode(VideoSize.UHD, 24)))
    }
}

private fun ByteArray.data(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toBytes(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
