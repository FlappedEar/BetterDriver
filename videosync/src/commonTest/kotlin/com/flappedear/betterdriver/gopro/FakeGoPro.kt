package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.IoProblem
import kotlinx.coroutines.flow.MutableSharedFlow

/** A camera on the other end of a [GoProLink]: answers every request like a HERO11 would, unless told otherwise. */
class FakeGoPro : GoProLink {
    val requests = mutableListOf<Pair<String, ByteArray>>()
    var reachable = true
    var connects = 0
    var disconnects = 0
    var busyPolls = 0
    var failShutter = false

    /** Settings the camera answers with status 2 (invalid parameter). */
    var refusedSettings = emptySet<Int>()

    /** Commands the camera does not have, answered with status 1 (a HERO9 lacks Set Local Date Time, 0x0F). */
    var refusedCommands = emptySet<Int>()

    /** Last value written for each setting id. */
    val settings = mutableMapOf<Int, Int>()
    var recording = false
    var ssid = "GP12345678"
    var password = "secret-pass"
    override val notifications = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    private val assemblers = mutableMapOf<String, OpenGoPro.Assembler>()

    val ids: List<Int> get() = requests.map { it.second[0].toInt() and 0xFF }

    override suspend fun connect() {
        if (!reachable) throw IoProblem("not found", null)
        connects++
    }

    override suspend fun write(characteristic: String, packet: ByteArray) {
        if (!reachable) throw IoProblem("link lost", null)
        val message = assemblers.getOrPut(characteristic) { OpenGoPro.Assembler() }.accept(packet) ?: return
        requests += characteristic to message
        val id = message[0].toInt() and 0xFF
        val reply: ByteArray = when {
            characteristic == GoProUuids.QUERY && id == 0x13 ->
                byteArrayOf(0x13, 0, 8, 1, if (busyPolls-- > 0) 1 else 0)
            characteristic == GoProUuids.SETTINGS && id != 0x5B -> setting(id, message[2].toInt() and 0xFF)
            refused(characteristic, id) -> byteArrayOf(message[0], 1)
            id == 0x01 -> byteArrayOf(0x01, 0).also { recording = message[2].toInt() == 1 }
            else -> byteArrayOf(message[0], 0)
        }
        for (p in OpenGoPro.packetize(reply)) notifications.emit(GoProUuids.responseFor(characteristic) to p)
    }

    private fun refused(characteristic: String, id: Int) =
        characteristic == GoProUuids.COMMAND && (id in refusedCommands || (id == 0x01 && failShutter))

    /** Set Setting: refused with status 2, or noted and answered with success. */
    private fun setting(id: Int, value: Int): ByteArray {
        if (id in refusedSettings) return byteArrayOf(id.toByte(), 2)
        settings[id] = value
        return byteArrayOf(id.toByte(), 0)
    }

    override suspend fun read(characteristic: String): ByteArray = when (characteristic) {
        GoProUuids.WIFI_AP_SSID -> ssid.encodeToByteArray()
        GoProUuids.WIFI_AP_PASSWORD -> password.encodeToByteArray()
        else -> throw IoProblem("unknown characteristic", null)
    }

    override suspend fun disconnect() {
        disconnects++
    }
}
