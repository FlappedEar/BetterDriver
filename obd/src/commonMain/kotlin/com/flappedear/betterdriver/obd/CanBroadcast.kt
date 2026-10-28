package com.flappedear.betterdriver.obd

/**
 * A value a car broadcasts on its CAN bus by itself, unasked (FBBD-263): steering angle, for one.
 * It is not polled; the [ElmPoller] listens for frames with [canId] between its requests. [requires]
 * are the 11-bit ids that must all be seen at the OBD port before the frame is read as this signal,
 * so another car's frame with the same id is not decoded with this [equation].
 *
 * [channel] is how the value is recorded and shown, like a polled channel. Its [PidChannel.mode] is
 * [BroadcastMode], which is never sent, and its [PidChannel.header] is [canId], so the poller never
 * asks for it. The [equation] reads the frame's 8 data bytes as `raw` (`A` is byte 0), as in a
 * RaceChrono CAN-Bus channel. [periodMs] is how often the car sends the frame.
 */
data class CanSignal(
    val id: String,
    val name: String,
    val canId: Int,
    val equation: String,
    val unit: String,
    val periodMs: Long,
    val requires: Set<Int> = setOf(canId),
    val pollClass: PollClass = PollClass.FAST,
) {
    val channel: PidChannel = PidChannel(id, name, canId, BroadcastMode, 0, equation, unit, pollClass)

    /** The value in one frame's data bytes, or null when the equation has none. */
    fun decode(data: ByteArray): Double? = channel.compiled?.evaluate(data)?.takeIf { it.isFinite() }

    companion object {
        /** The mode of a [CanSignal]'s channel: not a request, a frame the car sends by itself. */
        const val BroadcastMode = 0x3F
    }
}

/** The broadcast signals the app knows (FBBD-263), and which of them a car's OBD port carries. */
object CanBroadcasts {
    /** The FR-S frames all of its signals require: steering, speed and brake, pedal and rpm, temperatures. */
    private val FrsFrames = setOf(0x0D0, 0x0D1, 0x140, 0x360)

    @Suppress("LongParameterList")
    private fun frs(id: String, name: String, canId: Int, equation: String, unit: String, periodMs: Long) =
        CanSignal(
            id, name, canId, equation, unit, periodMs, FrsFrames,
            if (periodMs >= SLOW_PERIOD_MS) PollClass.SLOW else PollClass.FAST,
        )

    /**
     * 2013 Scion FR-S / Toyota 86 / Subaru BRZ (gen 1): steering angle in frame 0xD0, 0.1° per bit,
     * signed little-endian, positive turning left, 50 times a second. From the FT86 CAN map
     * (https://github.com/timurrrr/ft86/blob/main/can_bus/gen1.md), the formula Arek sent on
     * 2026-10-20, and checked on his car on 2026-10-23 (FBBD-319: -435° to +413° lock to lock).
     * Read through pins 6 and 14 of the OBD port. In degrees ("deg", plain ASCII for VBO readers).
     */
    val FrsSteering = frs("steering_angle", "Steering angle", 0x0D0, "bytesToIntLe(raw, 0, 2) * -0.1", "deg", 20)

    /**
     * FR-S brake pressure, frame 0xD1 byte 2 (`C`), 54 a second on Arek's car (FBBD-319). RaceChrono's
     * FT86 map scales it by 128 without naming the unit; read as kPa that is 1.28 bar a bit, which made
     * 63 bar for a hard press standing (medium confidence on the unit, FBBD-320). [FrsBrake] is the
     * same byte as the map's brake position: 100 % at 0x46.
     */
    val FrsBrakePressure = frs("brake_pressure", "Brake pressure", 0x0D1, "C * 1.28", "bar", 20)
    val FrsBrake = frs("brake", "Brake", 0x0D1, "MIN(C / 0.7, 100)", "%", 20)

    /**
     * FR-S frame 0x140, 100 a second: accelerator pedal (`A`), throttle plate (`G`), both in 255ths,
     * and engine speed in the low 14 bits of bytes 2 and 3 (750 rpm idle, 2160 on a blip, FBBD-319).
     */
    val FrsAccelerator = frs("accelerator", "Accelerator", 0x140, "A / 2.55", "%", 10)
    val FrsThrottle = frs("throttle", "Throttle", 0x140, "G / 2.55", "%", 10)
    val FrsRpm = frs("rpm", "RPM", 0x140, "(C + D * 256) & 0x3FFF", "rpm", 10)

    /** FR-S frame 0x360, 20 a second: engine oil (`C`) and coolant (`D`), 40 under. Same as 7E0 21 01 and 01 05. */
    val FrsOilTemp = frs("oil_temp", "Engine oil temp", 0x360, "C - 40", "°C", 50)
    val FrsCoolantTemp = frs("coolant_temp", "Coolant temp", 0x360, "D - 40", "°C", 50)

    /**
     * Everything the FR-S broadcasts that the app records (FBBD-320, Arek 2026-10-23: "Integrujemy CAN
     * skoro jest szybszy niż obd"). A polled channel with the same id is not asked for while these are
     * heard; only what the bus lacks (ATF temperature, intake air) is still polled.
     */
    val Frs = listOf(
        FrsSteering, FrsBrakePressure, FrsBrake, FrsAccelerator, FrsThrottle, FrsRpm, FrsOilTemp, FrsCoolantTemp,
    )

    /**
     * Toyota (Corolla and others): STEER_ANGLE_SENSOR, frame 0x025. STEER_ANGLE is 12 signed bits from
     * bit 4 (big-endian), 1.5° per bit, and STEER_FRACTION 4 signed bits from bit 32, 0.1° per bit,
     * added to it; positive turning left. From comma.ai's Toyota DBC
     * (https://github.com/commaai/opendbc, generator/toyota/_toyota_2017.dbc). Whether a 2023
     * Corolla puts it on the OBD port is not known: the connect probe's log line tells. The period
     * is an assumption (about 80 a second) and only spreads the timestamps of a listen's frames.
     * Required with it, so another make's 0x025 is not read as a steering angle: WHEEL_SPEEDS (0xAA)
     * and SPEED (0xB4) from the same DBC.
     */
    val ToyotaSteering = CanSignal(
        id = "steering_angle",
        name = "Steering angle",
        canId = 0x025,
        equation = "bitsToInt(raw, 4, 12) * 1.5 + bitsToInt(raw, 32, 4) * 0.1",
        unit = "°",
        periodMs = 12,
        requires = setOf(0x025, 0x0AA, 0x0B4),
    )

    val all: List<CanSignal> = Frs + ToyotaSteering

    /**
     * VINs of the cars whose broadcasts are listened for (FBBD-284): the first-generation Scion FR-S,
     * Toyota 86 / GT86 and Subaru BRZ, all built by Subaru with VINs from `JF1Z` (FR-S `JF1ZNAA1…`,
     * for example https://decodethis.com/web/vin/JF1ZNAA1XD1722071). Arek does not want listening on
     * the Corolla (2026-10-21), so no Toyota VIN is here. The second generation (GR86 / BRZ from
     * model year 2022) has `JF1Z` VINs too but other CAN frames, so only model years [Gen1Years]
     * (VIN position 10, `C` 2012 to `M` 2021) are taken.
     */
    val VinPrefixes = listOf("JF1Z")
    const val Gen1Years = "CDEFGHJKLM"

    /** Whether the car with [vin] is one whose broadcasts are listened for. */
    fun wantedFor(vin: String): Boolean {
        val upper = vin.uppercase()
        return VinPrefixes.any { upper.startsWith(it) } && upper.getOrNull(YEAR_AT)?.let { it in Gen1Years } == true
    }

    private const val YEAR_AT = 9

    /**
     * The [signals] to listen for on a car whose OBD port showed [seen] (id to frames in the probe), one
     * per channel id: a signal counts when every id it requires came at least [minFrames] times.
     */
    fun matching(seen: Map<Int, Int>, minFrames: Int = MIN_FRAMES, signals: List<CanSignal> = all): List<CanSignal> =
        signals.filter { s -> s.requires.all { (seen[it] ?: 0) >= minFrames } }.distinctBy { it.id }

    /** Frames of each required id a probe must have caught: a frame sent once a second can be missed. */
    const val MIN_FRAMES = 3

    /** A signal sent this far apart or more is a slow one, like a polled temperature. */
    private const val SLOW_PERIOD_MS = 50L
}

/** Reading what an ELM327 prints while it monitors the bus (`AT MA`) with headers on (FBBD-263). */
object CanMonitor {
    /**
     * The frames in [text]: id and data bytes. With headers on and spaces off an 11-bit frame prints
     * as three hex digits of id and then its data (`0D0` `1A2B…`), up to 8 bytes. Lines that are not
     * such a frame (`BUFFER FULL`, `STOPPED`, an echo, a half line) are skipped.
     */
    fun frames(text: String?): List<Pair<Int, ByteArray>> = text.orEmpty().split('\r', '\n').mapNotNull { raw ->
        val line = raw.trim().replace(" ", "").uppercase()
        if (!FRAME.matches(line)) return@mapNotNull null
        val id = line.substring(0, ID_DIGITS).toInt(HEX)
        val data = line.substring(ID_DIGITS).chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()
        id to data
    }

    /** How many frames of each id [text] holds. */
    fun count(text: String?): Map<Int, Int> = frames(text).groupingBy { it.first }.eachCount()

    /** The data bytes of each frame in [text] printed with headers off: one frame's data a line. */
    fun dataLines(text: String?): List<ByteArray> = text.orEmpty().split('\r', '\n').mapNotNull { raw ->
        val line = raw.trim().replace(" ", "").uppercase()
        if (!DATA.matches(line)) return@mapNotNull null
        line.chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()
    }

    private const val ID_DIGITS = 3
    private const val HEX = 16

    /** Three hex digits of 11-bit id, then 1 to 8 whole data bytes. */
    private val FRAME = Regex("""[0-7][0-9A-F]{2}([0-9A-F]{2}){1,8}""")
    private val DATA = Regex("""([0-9A-F]{2}){1,8}""")
}

/** The VIN in a `09 02` answer (FBBD-284). */
object Vin {
    /**
     * The 17 characters in [message], the whole answer (`49 02 01 4A 46 31 …`, the `01` being the
     * count of data items CAN adds), or null when it is not a VIN answer.
     */
    fun of(message: ByteArray): String? {
        val vinAnswer = message.size >= 2 && message[0].toInt() and ByteMask == MODE_09_ANSWER &&
            message[1].toInt() == VIN_PID
        if (!vinAnswer) return null
        val text = message.drop(2).map { (it.toInt() and ByteMask).toChar() }
            .filter { it in '0'..'9' || it in 'A'..'Z' }
        return text.takeLast(LENGTH).joinToString("").takeIf { it.length == LENGTH }
    }

    private const val LENGTH = 17
    private const val MODE_09_ANSWER = 0x49
    private const val VIN_PID = 0x02
}
