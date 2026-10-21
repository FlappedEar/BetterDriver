package com.flappedear.betterdriver.core.ble

/**
 * The larger MTU asked for once more after a link that came up without it starts streaming
 * (FBBD-282). On 21 Oct 2026 the RaceBox Micro streamed at the third setup, the one without the MTU
 * request ([SetupLadder]), and stayed at the default MTU for the whole drive: each 88-byte message
 * came in 5 notifications, and with a Bluetooth LE OBD adapter on the same radio the link stalled for
 * about 1.3 s nine times, each time losing a second of messages. So [afterMs] after the data starts
 * the link asks again, up to [tries] times while Android is busy with another request. A request
 * that goes unanswered before the connection drops is not made again for this link: the MTU stall
 * of 20 Oct (status 147) must not come back on every reconnect.
 */
class LateMtu(private val afterMs: Long = 5_000L, private val tries: Int = 5) {
    /** This connection came up without the MTU request and has not asked since. */
    private var due = false
    private var streamingSince: Long? = null
    private var left = 0

    /** Asked on this connection and not answered yet. */
    var waiting = false
        private set

    /** A late request went unanswered before a drop; never asked again on this link. */
    var givenUp = false
        private set

    /** A new connection, set up with or without the MTU request ([askedInSetup]). */
    fun newConnection(askedInSetup: Boolean) {
        due = !askedInSetup && !givenUp
        streamingSince = null
        left = tries
        waiting = false
    }

    /** Data flows at [nowMs]; true when the link should ask for the MTU now. */
    fun ask(nowMs: Long): Boolean {
        val since = streamingSince ?: nowMs.also { streamingSince = it }
        val now = due && nowMs - since >= afterMs
        if (now) {
            due = false
            waiting = true
        }
        return now
    }

    /** Android refused to send the request (another one is running): try again on a later tick. */
    fun notSent() {
        waiting = false
        left--
        due = left > 0
    }

    /** The device answered. */
    fun answered() {
        waiting = false
    }

    /** The connection ended; with a request still unanswered, the link stops asking. */
    fun closed() {
        if (waiting) givenUp = true
        waiting = false
        due = false
    }
}
