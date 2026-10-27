package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.obd.equation.Equation

/**
 * How often a channel is worth asking for (FBBD-53). [FAST] channels move with the driver's inputs
 * (rpm, speed, throttle, pedal, brake) and get most of the adapter's time; [SLOW] ones (temperatures,
 * fuel, voltage) change over seconds. See [PollScheduler].
 */
enum class PollClass { FAST, SLOW }

/**
 * One OBD-II channel (FBBD-53): the request [mode] and [pid] sent to [header], and the [equation]
 * that turns the response into a value in [unit]. The same shape as a RaceChrono OBD-II channel, so
 * profiles from its community carry over.
 *
 * [header] is the 11-bit or 29-bit CAN id the request goes to (the ELM327's `AT SH`), for example
 * 0x7E0 for the engine ECU; null is the adapter's default header, the functional broadcast that
 * every emissions ECU answers (0x7DF on 11-bit CAN). [pidLength] is 1 for modes 01 and 21 and 2 for
 * mode 22 (`22 F1 90`).
 *
 * [id] is stable: it names the channel in recorded data, and a car profile's channel replaces the
 * generic channel with the same id (see [CarProfile]).
 */
data class PidChannel(
    val id: String,
    val name: String,
    val header: Int?,
    val mode: Int,
    val pid: Int,
    val equation: String,
    val unit: String,
    val pollClass: PollClass,
    val pidLength: Int = 1,
) {
    init {
        require(mode in 1..MaxMode) { "mode $mode" }
        require(pidLength == 1 || pidLength == 2) { "pidLength $pidLength" }
        require(pid in 0 until (1 shl (Byte.SIZE_BITS * pidLength))) { "pid $pid" }
    }

    /** The parsed [equation], or null when it does not parse: the channel is then never shown. */
    val compiled: Equation? by lazy(LazyThreadSafetyMode.PUBLICATION) { Equation.parse(equation) }

    /** The request as the ELM327 takes it, for example "010C" or "2182". */
    val command: String get() = hex(mode, 2) + hex(pid, 2 * pidLength)

    /** A standard mode 01 PID asked of every ECU; whether the car has it is in the 01 00 bitmasks. */
    val isStandard: Boolean get() = mode == 0x01 && header == null

    /** What the scheduler and reader key requests by: channels with the same request share one. */
    val request: Request get() = Request(header, mode, pid, pidLength)

    /**
     * The value in one ECU's [message], the complete response with its echo (`41 0C 1A F8`), or null
     * when the message is not the answer to this request (another PID, a negative response 7F) or
     * the equation has no value for it.
     */
    fun decode(message: ByteArray): Double? {
        val data = request.dataOf(message) ?: return null
        return compiled?.evaluate(data)
    }

    /** The value from the first ECU in [messages] that answered this request, or null. */
    fun decode(messages: List<EcuMessage>): Double? =
        messages.firstNotNullOfOrNull { decode(it.bytes) }
}

/** One OBD-II request: what the adapter is asked. Several channels can read the same response. */
data class Request(val header: Int?, val mode: Int, val pid: Int, val pidLength: Int = 1) {
    /**
     * The data after the echo when [message] answers this request, else null. A positive response
     * starts with the mode plus 0x40 and repeats the PID: `41 0C …` for 01 0C, `61 01 …` for 21 01.
     */
    @Suppress("ReturnCount") // Guard clauses: each is one way the message is not this answer.
    fun dataOf(message: ByteArray): ByteArray? {
        val echo = 1 + pidLength
        if (message.size < echo || message[0].toInt() and ByteMask != mode + PositiveResponse) return null
        var echoedPid = 0
        for (i in 1 until echo) echoedPid = (echoedPid shl Byte.SIZE_BITS) or (message[i].toInt() and ByteMask)
        if (echoedPid != pid) return null
        return message.copyOfRange(echo, message.size)
    }
}

internal fun hex(value: Int, digits: Int): String = value.toString(HexRadix).uppercase().padStart(digits, '0')

/** A positive response's first byte is the request mode plus this. */
internal const val PositiveResponse = 0x40

internal const val ByteMask = 0xFF
private const val HexRadix = 16

/** Modes are 0x01..0x3F; their positive responses are 0x41..0x7F. */
private const val MaxMode = 0x3F
