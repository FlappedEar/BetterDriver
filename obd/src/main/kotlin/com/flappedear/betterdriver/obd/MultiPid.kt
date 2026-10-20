package com.flappedear.betterdriver.obd

/**
 * Several mode 01 PIDs in one request (FBBD-270). On CAN an ECU answers a request of up to six
 * mode 01 PIDs (`01 0C 0D 11`) with all the ones it has in one message (`41 0C 1A F8 0D 32 11 7F`),
 * SAE J1979 / ISO 15031-5, so one adapter round trip reads several channels. The adapter only
 * forwards the bytes; whether it works depends on the ECU, so [ElmPoller] checks it once per
 * connection. An answer longer than one CAN frame comes back as an ISO-TP multi-frame message,
 * which the adapter and [ElmParser] put together.
 */
object MultiPid {
    /** J1979 allows at most six PIDs per mode 01 request. */
    const val MAX_PIDS = 6

    /**
     * Data bytes of each mode 01 PID's answer (SAE J1979; https://en.wikipedia.org/wiki/OBD-II_PIDs#Service_01),
     * for the PIDs a channel reads. A PID not listed here is always asked on its own.
     */
    val dataLength: Map<Int, Int> = mapOf(
        0x04 to 1, 0x05 to 1, 0x0B to 1, 0x0C to 2, 0x0D to 1, 0x0E to 1, 0x0F to 1, 0x10 to 2, 0x11 to 1,
        0x24 to 4, 0x2F to 1, 0x42 to 2, 0x44 to 2, 0x46 to 1, 0x49 to 1, 0x4A to 1, 0x5A to 1, 0x5C to 1,
    )

    /**
     * Channel ids asked first, so the driver's inputs share the first request: engine speed, road
     * speed, throttle and pedal. The rest follow in the order given.
     */
    private val first = listOf("rpm", "speed", "throttle", "accelerator", "pedal_d", "pedal_e")

    /** Whether [channel] can be asked together with other PIDs. */
    fun groupable(channel: PidChannel): Boolean =
        channel.mode == 0x01 && channel.header == null && channel.pidLength == 1 && channel.pid in dataLength

    /**
     * [channels] as requests: channels with the same [Request] always share one; [groupable] ones of
     * the same [PollClass] are packed, driver inputs first, up to [MAX_PIDS] to a request. With
     * [combine] false every request is a single PID, as before FBBD-270.
     */
    fun groups(channels: List<PidChannel>, combine: Boolean = true): List<List<PidChannel>> {
        val byRequest = channels.groupBy { it.request }.values.toList()
        if (!combine) return byRequest
        val ordered = byRequest.sortedBy { group ->
            first.indexOf(group.first().id).let { if (it < 0) first.size else it }
        }
        val out = ArrayList<Pack>()
        for (group in ordered) {
            val lead = group.first()
            if (!groupable(lead)) {
                out += Pack(group.toMutableList(), poll = null)
                continue
            }
            val poll = if (group.any { it.pollClass == PollClass.FAST }) PollClass.FAST else PollClass.SLOW
            val target = out.firstOrNull { it.poll == poll && it.pids < MAX_PIDS }
            if (target != null) {
                target.channels += group
                target.pids++
            } else {
                out += Pack(group.toMutableList(), poll)
            }
        }
        return out.map { it.channels }
    }

    /** A request being filled: its channels, their class (null: not groupable) and how many PIDs. */
    private class Pack(val channels: MutableList<PidChannel>, val poll: PollClass?) {
        var pids = 1
    }

    /** The request for [group] as the ELM327 takes it: `010C0D11`, or the one channel's command. */
    fun command(group: List<PidChannel>): String {
        val pids = group.map { it.pid }.distinct()
        if (pids.size == 1) return group.first().command
        return "01" + pids.joinToString("") { hex(it, 2) }
    }

    /**
     * One ECU's answer to a multi-PID request split per PID, each as a single-PID answer
     * (`41 0C 1A F8`) that [PidChannel.decode] reads; empty when [message] is not a mode 01 answer.
     * Stops at a PID whose length is unknown, as the rest cannot be told apart.
     */
    fun split(message: ByteArray): Map<Int, ByteArray> {
        if (message.isEmpty() || message[0].toInt() and ByteMask != 0x01 + PositiveResponse) return emptyMap()
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 1
        var length = lengthAt(message, i)
        while (length != null) {
            out[message[i].toInt() and ByteMask] = byteArrayOf(message[0], message[i]) +
                message.copyOfRange(i + 1, i + 1 + length)
            i += 1 + length
            length = lengthAt(message, i)
        }
        return out
    }

    /** The data length of the PID at [i] in [message], or null when there is none or it is cut off. */
    private fun lengthAt(message: ByteArray, i: Int): Int? {
        if (i >= message.size) return null
        return dataLength[message[i].toInt() and ByteMask]?.takeIf { i + 1 + it <= message.size }
    }
}
