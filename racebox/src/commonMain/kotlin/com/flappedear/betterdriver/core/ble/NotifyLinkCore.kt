package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine

/**
 * Setups in a row without data (about 2 minutes) after which the link stops its rounds and waits
 * quietly for the device (FBBD-281). On 21 Oct 2026 an unworn heart rate strap cost 55 rounds in
 * 18 minutes; a strap sends nothing until it is worn, and that is no fault (Arek).
 */
const val QuietAfterSetups = 6

/**
 * How the next setup connects after [setupsWithoutData] setups in a row brought nothing: the
 * [ladder]'s plan, or from [QuietAfterSetups] on one background connection with no time limit
 * (FBBD-281). The phone then connects on its own once the device advertises (a strap put on, a
 * receiver switched on), with no rounds, no log lines and no retries until then.
 */
fun notifyPlan(setupsWithoutData: Int, ladder: SetupLadder, quietAfter: Int = QuietAfterSetups): ConnectPlan =
    if (setupsWithoutData >= quietAfter) {
        ConnectPlan(autoConnect = true, askMtu = false, setupTimeoutMs = Long.MAX_VALUE)
    } else {
        ladder.plan()
    }

/**
 * How far a notify link's setup got (FBBD-279): the reason given when it brings no data names the
 * step it was waiting for, so a drive's log tells a device that never answered from one that
 * connected and sent nothing.
 */
enum class NotifyStep(private val waitingFor: String) {
    CONNECT("no answer to the connection"),
    MTU("no MTU answer"),
    SERVICES("services not found"),
    NOTIFICATIONS("notifications not confirmed"),
    FIRST_VALUE("notifications on, no value sent"),
    ;

    /** The reconnect reason after [elapsedMs] without data; it starts as before FBBD-279. */
    fun noDataReason(elapsedMs: Long): String = "no data after connecting: $waitingFor in $elapsedMs ms"
}

/**
 * What a [NotifyLinkCore] asks of the platform's Bluetooth: Android's GATT, the iPhone's
 * CoreBluetooth. Called on the link's own thread or queue.
 */
interface NotifyLinkIo {
    /**
     * Starts a connection made as [plan] says, as far as the platform can (iOS has no MTU request
     * or background connection of its own: a pending connect waits for the device). Null when it
     * started, else why it could not.
     */
    fun open(plan: ConnectPlan): String?

    /** Ends the connection; [setupFailed] when it brought no data (Android forgets its cached services). */
    fun close(setupFailed: Boolean)

    /** Runs [action] on the link's thread after [delayMs]. */
    fun later(delayMs: Long, action: () -> Unit)

    /** What the "connecting" line adds about the device (Android: whether it is paired). */
    fun detail(): String? = null
}

/**
 * The life cycle of a Bluetooth LE link to one device that notifies a standard characteristic
 * (Heart Rate Measurement, Location and Speed, ...), the same on Android and the iPhone (FBBD-358):
 * connect, subscribe, and reconnect after a drop, a failure, no data within the setup time of
 * connecting (15 s, 30 s on a background connection), or [silenceTimeoutMs] without data, until
 * [stop]. Setups that bring no data change how the next one connects ([SetupLadder]); after
 * [quietAfter] of them (6 by default) the link waits on one connection with no time limit (FBBD-281). Each
 * step is logged with the tag "Link" (FBBD-279), the same lines on both platforms.
 *
 * Not thread-safe: the platform calls it on one thread or queue, [tick] once a second. [onState]
 * gets each new [LinkState]; [LinkState.Streaming] carries the values in the last second.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One function per step the platform reports.
class NotifyLinkCore(
    private val address: String,
    private val io: NotifyLinkIo,
    /** The link's clock, in milliseconds. */
    private val now: () -> Long,
    private val onState: (LinkState) -> Unit = {},
    private val silenceTimeoutMs: Long = 5_000L,
    private val setupTimeoutMs: Long = SetupTimeoutMs,
    backgroundSetupTimeoutMs: Long = BackgroundSetupTimeoutMs,
    private val policy: ReconnectPolicy = ReconnectPolicy(FirstRetryMs, MaxRetryMs),
    /** Setups without data before the quiet wait; [Int.MAX_VALUE] for a link that never waits (RaceBox). */
    private val quietAfter: Int = QuietAfterSetups,
) {
    private val ladder = SetupLadder(setupTimeoutMs, backgroundSetupTimeoutMs)
    private var running = false
    private var connectionOpen = false
    private var setupStartedAt = 0L
    private var setupsWithoutData = 0

    /** When the current setup is given up without data; moved on connecting after a quiet wait. */
    private var setupDeadlineAt = 0L
    private var lastDataAt = 0L
    private var received = 0

    /** Counts the scheduled reconnects, so one from before a [stop] or a newer retry does nothing. */
    private var generation = 0

    var state: LinkState = LinkState.Idle
        private set

    /** How the current setup connects. */
    var plan: ConnectPlan = ladder.plan()
        private set

    /** Where the current setup is. */
    var step: NotifyStep = NotifyStep.CONNECT
        private set

    /** Setups in a row that ended before any data, counted from the step that last worked. */
    val failures: Int get() = ladder.failures

    fun start() {
        if (running) return
        running = true
        connect()
    }

    /** Disconnects; the link can be started again. */
    fun stop() {
        running = false
        generation++
        closeConnection(setupFailed = false)
        setState(LinkState.Idle)
    }

    /** The device answered the connection. */
    fun connected() {
        if (!connectionOpen) return
        log("connected in ${elapsed()} ms ($plan)")
        // After a quiet wait of any length, the rest of the setup gets the usual time.
        if (setupDeadlineAt == Long.MAX_VALUE) setupDeadlineAt = now() + setupTimeoutMs
    }

    /** The setup moved on to [next]. */
    fun reached(next: NotifyStep) {
        step = next
    }

    /** The setup or the connection failed for [reason]: a new one after the policy's delay. */
    fun failed(reason: String) = retry(reason)

    /** A value came from the device. */
    fun data() {
        if (!connectionOpen) return
        if (lastDataAt == 0L) log("first value, ${elapsed()} ms after connecting began")
        lastDataAt = now()
        received++
    }

    /** Once a second: report the rate, and restart a link that went silent or never finished setting up. */
    fun tick() {
        if (!running) return
        val t = now()
        val count = received
        received = 0
        val last = lastDataAt
        when {
            !connectionOpen -> Unit
            last != 0L && t - last > silenceTimeoutMs -> retry("no data for ${t - last} ms")
            last == 0L && t > setupDeadlineAt -> retry(step.noDataReason(t - setupStartedAt))
            last != 0L -> {
                policy.reset()
                ladder.streaming()
                setupsWithoutData = 0
                setState(LinkState.Streaming(count))
            }
        }
    }

    /** A line about this link in the debug log, as each platform's link writes its own steps. */
    fun log(text: String) = logLine(CoreLog.LevelInfo, Tag, "$address $text", null, null)

    /** Milliseconds since the current setup began. */
    fun elapsed(): Long = now() - setupStartedAt

    private fun connect() {
        if (!running) return
        closeConnection(setupFailed = false)
        setState(LinkState.Connecting)
        // The state's listener may have stopped the link (a strap off for the session).
        if (!running) return
        setupStartedAt = now()
        lastDataAt = 0L
        plan = notifyPlan(setupsWithoutData, ladder, quietAfter)
        val timeout = plan.setupTimeoutMs
        setupDeadlineAt = if (timeout == Long.MAX_VALUE) timeout else setupStartedAt + timeout
        step = NotifyStep.CONNECT
        if (setupsWithoutData == quietAfter) {
            log("$setupsWithoutData setups without data: waiting quietly until it advertises")
        }
        val detail = io.detail()?.let { ", $it" }.orEmpty()
        log("connecting ($plan$detail, setup ${ladder.failures})")
        val failure = io.open(plan)
        if (failure == null) connectionOpen = true else retry(failure)
    }

    private fun retry(reason: String) {
        if (!running) return
        val setupFailed = connectionOpen && lastDataAt == 0L
        if (setupFailed) {
            ladder.failed()
            setupsWithoutData++
        }
        closeConnection(setupFailed)
        val delay = policy.nextDelayMs()
        setState(LinkState.Reconnecting(policy.attempts, delay, reason))
        val mine = ++generation
        io.later(delay) { if (mine == generation) connect() }
    }

    private fun closeConnection(setupFailed: Boolean) {
        if (!connectionOpen) return
        connectionOpen = false
        io.close(setupFailed)
    }

    private fun setState(s: LinkState) {
        if (state == s) return
        state = s
        onState(s)
    }

    companion object {
        const val SetupTimeoutMs = 15_000L
        const val BackgroundSetupTimeoutMs = 30_000L

        /** Retries as for an NMEA receiver: 1 s, 2 s, 4 s, then every 5 s. */
        const val FirstRetryMs = 1_000L
        const val MaxRetryMs = 5_000L
        private const val Tag = "Link"
    }
}

/**
 * Writes a link's state to the debug log (FBBD-146): every change except the message rate of a
 * link that keeps streaming, which updates every second.
 */
fun logLink(address: String, s: LinkState, wasStreaming: Boolean) {
    if (s is LinkState.Streaming && wasStreaming) return
    val level = if (s is LinkState.Reconnecting) CoreLog.LevelWarn else CoreLog.LevelInfo
    logLine(level, "Link", "$address $s", null, null)
}
