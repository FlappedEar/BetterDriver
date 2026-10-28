@file:Suppress("MagicNumber") // Header bits, command ids and byte layouts of the Open GoPro BLE protocol.

package com.flappedear.betterdriver.gopro

/**
 * Open GoPro BLE UUIDs. GP-XXXX expands to b5f9XXXX-aa8d-11e3-9046-0002a5d5c51b.
 * Source: https://gopro.github.io/OpenGoPro/ble/protocol/ble_setup.html
 */
object GoProUuids {
    /** Control & Query service (16-bit 0xFEA6), which the camera advertises. */
    const val CONTROL_SERVICE = "0000fea6-0000-1000-8000-00805f9b34fb"
    val WIFI_AP_SERVICE = gp(0x0001)
    val WIFI_AP_SSID = gp(0x0002)
    val WIFI_AP_PASSWORD = gp(0x0003)
    val COMMAND = gp(0x0072)
    val COMMAND_RESPONSE = gp(0x0073)
    val SETTINGS = gp(0x0074)
    val SETTINGS_RESPONSE = gp(0x0075)
    val QUERY = gp(0x0076)
    val QUERY_RESPONSE = gp(0x0077)

    /** The response characteristic that answers writes to [request]. */
    fun responseFor(request: String): String = when (request) {
        COMMAND -> COMMAND_RESPONSE
        SETTINGS -> SETTINGS_RESPONSE
        QUERY -> QUERY_RESPONSE
        else -> throw IllegalArgumentException("not a request characteristic: $request")
    }

    private fun gp(id: Int) = "b5f9${id.toString(16).padStart(4, '0')}-aa8d-11e3-9046-0002a5d5c51b"
}

/**
 * Open GoPro BLE messages: packet framing and the TLV commands BetterDriver uses.
 *
 * Framing (https://gopro.github.io/OpenGoPro/ble/protocol/data_protocol.html): packets are at most
 * 20 bytes. A message starts with a general header (bits 6-5 = 00, 5-bit length), an extended
 * 13-bit header (01, 13-bit length over two bytes) or, received only, an extended 16-bit header
 * (10, then a 2-byte length). Further packets start with a continuation byte (bit 7 set, 4-bit
 * counter). Multi-byte values are big-endian.
 *
 * TLV: a request is an id followed by parameters, each a 1-byte length and its value; a response
 * is the id, a status byte (0 success, 1 error, 2 invalid parameter) and an optional payload.
 */
@Suppress("TooManyFunctions") // One function per Open GoPro message.
object OpenGoPro {
    const val MaxPacketBytes = 20
    private const val GeneralMaxLength = 0x1F
    private const val Ext13MaxLength = 0x1FFF

    /** Splits [message] into packets: a general header when it fits one packet, otherwise extended 13-bit. */
    fun packetize(message: ByteArray): List<ByteArray> {
        require(message.size <= Ext13MaxLength) { "message too long: ${message.size}" }
        if (message.size <= MaxPacketBytes - 1 && message.size <= GeneralMaxLength) {
            return listOf(byteArrayOf(message.size.toByte()) + message)
        }
        val packets = mutableListOf<ByteArray>()
        val header = byteArrayOf((0x20 or (message.size shr 8)).toByte(), (message.size and 0xFF).toByte())
        var pos = minOf(message.size, MaxPacketBytes - header.size)
        packets += header + message.copyOfRange(0, pos)
        var counter = 0
        while (pos < message.size) {
            val end = minOf(message.size, pos + MaxPacketBytes - 1)
            packets += byteArrayOf((0x80 or (counter and 0x0F)).toByte()) + message.copyOfRange(pos, end)
            counter++
            pos = end
        }
        return packets
    }

    /** Joins packets from one response characteristic back into messages. Not thread-safe. */
    class Assembler {
        private var buffer = ByteArray(0)
        private var remaining = 0

        /** Adds [packet]; returns the message once it is complete, else null. */
        @Suppress("ReturnCount") // One early return per malformed-packet case.
        fun accept(packet: ByteArray): ByteArray? {
            if (packet.isEmpty()) return null
            val first = packet[0].toInt() and 0xFF
            val body: ByteArray
            if (first and 0x80 != 0) {
                if (remaining <= 0) return null // A stray continuation: nothing to continue.
                body = packet.copyOfRange(1, packet.size)
            } else {
                buffer = ByteArray(0)
                when ((first shr 5) and 0x03) {
                    0 -> {
                        remaining = first and 0x1F
                        body = packet.copyOfRange(1, packet.size)
                    }
                    1 -> {
                        if (packet.size < 2) return null
                        remaining = ((first and 0x1F) shl 8) or (packet[1].toInt() and 0xFF)
                        body = packet.copyOfRange(2, packet.size)
                    }
                    2 -> {
                        if (packet.size < 3) return null
                        remaining = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
                        body = packet.copyOfRange(3, packet.size)
                    }
                    else -> return null
                }
            }
            val take = minOf(body.size, remaining)
            buffer += body.copyOfRange(0, take)
            remaining -= take
            return if (remaining == 0) buffer.also { buffer = ByteArray(0) } else null
        }
    }

    // --- Requests (https://gopro.github.io/OpenGoPro/ble/features/control.html) ---

    /** Set Shutter, command 0x01: start (true) or stop (false) recording. */
    fun shutter(on: Boolean) = Request(GoProUuids.COMMAND, byteArrayOf(0x01, 0x01, if (on) 1 else 0))

    /** Set AP Control, command 0x17: turn the camera's Wi-Fi access point on or off. */
    fun wifiAp(on: Boolean) = Request(GoProUuids.COMMAND, byteArrayOf(0x17, 0x01, if (on) 1 else 0))

    /** Sleep, command 0x05. The camera stays connectable over BLE. */
    fun sleep() = Request(GoProUuids.COMMAND, byteArrayOf(0x05))

    /** Get Hardware Info, command 0x3C. */
    fun hardwareInfo() = Request(GoProUuids.COMMAND, byteArrayOf(0x3C))

    /**
     * Set Date Time, command 0x0D: year (2 bytes), month, day, hour, minute, second, without time
     * zone. The HERO9 and HERO10 take only this one (Open GoPro references.json).
     */
    fun setDateTime(local: CameraDateTime): Request {
        val b = byteArrayOf(
            (local.year shr 8).toByte(), local.year.toByte(),
            local.month.toByte(), local.day.toByte(),
            local.hour.toByte(), local.minute.toByte(), local.second.toByte(),
        )
        return Request(GoProUuids.COMMAND, byteArrayOf(0x0D, b.size.toByte()) + b)
    }

    /**
     * Set Local Date Time, command 0x0F (HERO11 and later): year (2 bytes), month, day, hour,
     * minute, second, UTC offset in minutes (signed, 2 bytes) and a DST flag.
     */
    fun setLocalDateTime(local: CameraDateTime, utcOffsetMinutes: Int, dst: Boolean): Request {
        val b = byteArrayOf(
            (local.year shr 8).toByte(), local.year.toByte(),
            local.month.toByte(), local.day.toByte(),
            local.hour.toByte(), local.minute.toByte(), local.second.toByte(),
            (utcOffsetMinutes shr 8).toByte(), utcOffsetMinutes.toByte(),
            if (dst) 1 else 0,
        )
        return Request(GoProUuids.COMMAND, byteArrayOf(0x0F, b.size.toByte()) + b)
    }

    /**
     * Keep alive: setting 0x5B with value 0x42, written to the settings characteristic. The Open
     * GoPro Python SDK sends it every [KeepAliveIntervalMillis] (open_gopro 0.22.0,
     * gopro_wireless.py, keep_alive_interval default 3 s).
     */
    fun keepAlive() = Request(GoProUuids.SETTINGS, byteArrayOf(0x5B, 0x01, 0x42))

    const val KeepAliveIntervalMillis = 3_000L

    /**
     * Setting ids and values (open_gopro 0.22.0, models/constants/settings.py). Which values each
     * camera takes is from the Open GoPro openapi.json (gh-pages, Setting_2 and Setting_3
     * x-option-metadata); the app's per-camera list is GoProModel in :setup.
     */
    object Setting {
        /**
         * Set Setting: the setting id, then a 1-byte value, written to the settings characteristic.
         * The camera answers on the settings response with the setting id and a status.
         */
        fun set(id: Int, value: Int) = Request(GoProUuids.SETTINGS, byteArrayOf(id.toByte(), 0x01, value.toByte()))

        const val VIDEO_RESOLUTION = 2
        const val FRAMES_PER_SECOND = 3

        const val RES_4K = 1
        const val RES_2_7K = 4
        const val RES_1080 = 9
        const val RES_720 = 12

        const val FPS_60 = 5
        const val FPS_30 = 8
    }

    /** Get Status Values, query 0x13, for [ids]. */
    fun statuses(vararg ids: Int) = Request(GoProUuids.QUERY, byteArrayOf(0x13) + ids.map { it.toByte() })

    /** Status ids (open_gopro 0.22.0, models/constants/statuses.py). */
    object Status {
        const val BUSY = 8
        const val ENCODING = 10
    }

    class Request(val characteristic: String, val message: ByteArray) {
        /** The request id as the camera's errors name it, such as "0x01". */
        val hexId: String get() = "0x" + id.toString(16).padStart(2, '0')

        val id: Int get() = message[0].toInt() and 0xFF
    }

    /** A TLV response: its id, status byte and the rest. */
    class Response(val id: Int, val status: Int, val payload: ByteArray) {
        val ok: Boolean get() = status == 0

        companion object {
            fun parse(message: ByteArray): Response? = if (message.size < 2) {
                null
            } else {
                Response(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF, message.copyOfRange(2, message.size))
            }
        }
    }

    /** Query payload: repeated (id, length, value). */
    fun parseStatuses(payload: ByteArray): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        var i = 0
        while (i + 2 <= payload.size) {
            val id = payload[i].toInt() and 0xFF
            val len = payload[i + 1].toInt() and 0xFF
            if (i + 2 + len > payload.size) break
            out[id] = payload.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return out
    }

    /** True when a status value (any length) is non-zero. */
    fun flag(value: ByteArray?): Boolean = value?.any { it.toInt() != 0 } ?: false

    data class HardwareInfo(
        val modelNumber: Int,
        val modelName: String,
        val firmware: String,
        val serial: String,
        val apSsid: String,
        val apMac: String,
    ) {
        companion object {
            private const val Fields = 7

            /**
             * Hardware info payload: each field is a 1-byte length and its value; model number and
             * board type are 4-byte integers (layout from open_gopro 0.22.0, api/ble_commands.py).
             */
            fun parse(payload: ByteArray): HardwareInfo? {
                val fields = mutableListOf<ByteArray>()
                var i = 0
                while (i < payload.size && fields.size < Fields) {
                    val len = payload[i].toInt() and 0xFF
                    if (i + 1 + len > payload.size) break
                    fields += payload.copyOfRange(i + 1, i + 1 + len)
                    i += 1 + len
                }
                if (fields.size < Fields) return null
                val model = fields[0].fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val text = { f: ByteArray -> f.decodeToString() }
                return HardwareInfo(
                    modelNumber = model,
                    modelName = text(fields[1]),
                    firmware = text(fields[3]),
                    serial = text(fields[4]),
                    apSsid = text(fields[5]),
                    apMac = text(fields[6]),
                )
            }
        }
    }
}

/** A local date and time as the camera's clock takes it (month and day from 1). */
data class CameraDateTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)
