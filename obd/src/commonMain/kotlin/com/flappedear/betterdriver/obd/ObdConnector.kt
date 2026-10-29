package com.flappedear.betterdriver.obd

import kotlin.concurrent.Volatile

/** What an [ObdConnector] reports, on its thread. */
@Suppress("LongParameterList") // ready() carries everything the car and adapter answered.
interface ObdConnectorListener {
    /** The adapter [target] set up and the car answered: [channels] are polled from now. */
    fun ready(
        target: ObdTarget,
        adapter: String,
        protocol: String,
        voltage: String?,
        supported: Set<Int>,
        channels: List<PidChannel>,
    ) {}

    /** Polling switched to [channels] on the same connection (FBBD-283). */
    fun polling(target: ObdTarget, channels: List<PidChannel>) {}

    /** [channel] read [value] at [atMs] on the connector's clock. */
    fun value(channel: PidChannel, value: Double, atMs: Long) {}

    /** A connection to [target] that opened has ended, with [reason]. */
    fun ended(target: ObdTarget, reason: String) {}

    /** A line for the app log. */
    fun log(text: String) {}

    /** What the car at [target] offers (FBBD-344), when the connector scans it. */
    fun scanned(target: ObdTarget, scan: CarScan) {}
}

/**
 * Keeps an OBD-II adapter connected (FBBD-15, FBBD-121), whatever carries it. Each round asks
 * [targets] for the adapters to try, in order (the app puts paired Bluetooth adapters before the
 * Wi-Fi one), and runs an [ElmPoller] on the first that opens. Only when none of them opens is
 * [fallback] asked (the app scans for a Bluetooth LE adapter there, which takes a few seconds).
 * An ended connection is retried soon; while no adapter answers the wait grows to [retryMs]'s last,
 * and [retryNow] cuts the wait short (the app calls it when Wi-Fi or Bluetooth comes back, FBBD-271).
 * A connection the car answered on that is lost mid-drive (the link dropped or the adapter went
 * silent, not the car switched off) is retried every [lostRetryMs] for [lostFastMs] instead, so car
 * data comes back within seconds of the adapter, and the log says how long it was gone.
 *
 * [run] blocks until [running] turns false; [stop] closes the open connection so it returns. No
 * exception ends the loop while [running] holds: a round that fails in an unexpected way is logged
 * and retried, and an interrupted one ends quietly (FBBD-269). The [tracker] follows every step for
 * the OBD page. [pollSet] is what each connection reads (FBBD-283). [profile] is the car's, asked at
 * each connection, with the driver's own parameters (FBBD-344); while [scan] holds, a connection
 * first scans what the car offers ([ObdConnectorListener.scanned]). Common Kotlin, so Android and
 * iOS run the same loop; it is tested on the JVM against simulated adapters.
 */
@Suppress("LongParameterList") // Collaborators, each replaced in the tests.
class ObdConnector(
    private val targets: () -> List<ObdTarget>,
    private val listener: ObdConnectorListener,
    private val tracker: ObdStatusTracker,
    private val clock: ObdClock,
    private val profile: () -> CarProfile = { CarProfile.Generic },
    private val fallback: () -> List<ObdTarget> = { emptyList() },
    private val retryMs: List<Long> = RETRY_MS,
    private val pollSet: () -> PollSet = { PollSet.All },
    private val scan: () -> Boolean = { false },
    private val lostRetryMs: Long = LOST_RETRY_MS,
    private val lostFastMs: Long = LOST_FAST_MS,
) {
    @Volatile
    private var running: () -> Boolean = { false }

    @Volatile
    private var open: ObdConnection? = null

    @Volatile
    private var retrySoon = false

    private var lastNote: String? = null
    private var lastNoteMs = 0L
    private var searchingSinceMs = 0L

    /** When a live connection was last lost mid-drive (FBBD-271); null once car data is back. */
    private var lostAtMs: Long? = null

    /** Whether the car answered on the connection open now. */
    @Volatile
    private var answered = false

    @Suppress("TooGenericExceptionCaught") // Any failed round is retried, never a crash (FBBD-269).
    fun run(running: () -> Boolean) {
        this.running = running
        var failures = 0
        while (running()) {
            val reason = try {
                connectOnce()
            } catch (e: IOException) {
                "connection lost: ${e.message}"
            } catch (e: RuntimeException) {
                if (isNoPermission(e)) {
                    "no permission: ${e.message}"
                } else {
                    // A bug in one round must not end car data for the session, nor the recorder process.
                    listener.log("round failed: $e")
                    "failed: $e"
                }
            } catch (e: Exception) {
                // Only stopping interrupts; the loop condition ends the run.
                if (!isInterrupted(e)) throw e
                "interrupted"
            }
            if (!running()) break
            // A connection that opened and ended is retried soon; no adapter at all backs off,
            // except shortly after a live connection was lost, when the adapter is most likely back soon.
            failures = if (reason == null) failures + 1 else 0
            val lost = lostAtMs
            val fast = lost != null && clock.nowMs() - lost < lostFastMs
            await(if (fast) lostRetryMs else retryMs[(failures - 1).coerceIn(0, retryMs.size - 1)])
        }
    }

    /** Closes the open connection, so a blocked [run] notices [running] has turned false. */
    fun stop() {
        open?.close()
    }

    /**
     * Ends the wait before the next try now (FBBD-271): the app calls it when a Wi-Fi network or a
     * Bluetooth device comes back, so a lost adapter is tried again at once, not up to 20 s later.
     */
    fun retryNow() {
        retrySoon = true
    }

    /**
     * Ends the open connection and connects again at once (FBBD-344): the next connection reads
     * [profile] and [scan] afresh, so a scan asked for or a parameter being tried takes effect.
     */
    fun restart() {
        retrySoon = true
        open?.close()
    }

    /** Waits [ms] in short steps, ending early on [retryNow] or when [running] turns false. */
    private fun await(ms: Long) {
        val end = clock.nowMs() + ms
        while (running() && !retrySoon) {
            val left = end - clock.nowMs()
            if (left <= 0) break
            clock.sleep(left.coerceAtMost(WAIT_STEP_MS))
        }
        retrySoon = false
    }

    /** One round; null when no adapter opened (already noted), else why the connection ended. */
    @Suppress("ReturnCount") // Each early return is one way no connection opened.
    private fun connectOnce(): String? {
        val errors = ArrayList<String>()
        val opened = openFirst(targets(), errors) ?: if (running()) openFirst(fallback(), errors) else null
        if (opened == null) {
            if (errors.isEmpty()) {
                note("no adapter: none listed, or the phone is not on the Wi-Fi adapter's network", "No adapter to try")
            } else {
                note("no adapter answered: ${errors.joinToString("; ")}", "No adapter answered · trying again")
            }
            return null
        }
        val (target, line) = opened
        lastNote = null
        // Wi-Fi or Bluetooth news from before this connection says nothing about the next drop.
        retrySoon = false
        val at = target.address?.let { " at $it" }.orEmpty()
        listener.log("connected to ${target.where}$at over ${target.transport.label}")
        tracker.connecting(target.name)
        answered = false
        val reason = try {
            ElmPoller(line, profile(), Poll(target), running, clock, pollSet = pollSet, scan = scan()).run()
        } catch (e: IOException) {
            // The link dropped or a write failed: the usual way a connection ends.
            "connection lost: ${e.message}"
        } finally {
            open?.close()
            open = null
        }
        if (answered && running() && isLinkLoss(reason)) lostAtMs = clock.nowMs()
        listener.log("connection to ${target.where} ended: $reason")
        tracker.ended(reason)
        listener.ended(target, reason)
        return reason
    }

    /** Opens the first of [all] that connects, with its line; adds why each other one failed to [errors]. */
    private fun openFirst(all: List<ObdTarget>, errors: MutableList<String>): Pair<ObdTarget, ElmLine>? =
        all.asSequence().takeWhile { running() }.firstNotNullOfOrNull { t -> tryOpen(t, errors)?.let { t to it } }

    /** The line to [t] once it opened, or null after adding why it did not to [errors]. */
    // Opened, or one of two ways it failed; a missing permission is the platform's RuntimeException.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun tryOpen(t: ObdTarget, errors: MutableList<String>): ElmLine? {
        lateinit var connection: ObdConnection
        val line = ElmLine(write = { connection.write(it) })
        connection = try {
            t.open(object : ObdBytes {
                override fun received(bytes: ByteArray) = line.feed(bytes)

                override fun closed(reason: String) = line.close(reason)
            })
        } catch (e: IOException) {
            errors += "${t.where} ${e.message}"
            return null
        } catch (e: RuntimeException) {
            if (!isNoPermission(e)) throw e
            errors += "${t.where} no permission: ${e.message}"
            return null
        }
        open = connection
        if (!running()) connection.close()
        return line
    }

    /**
     * Logs [text] once until it changes, so a missing adapter is not logged every few seconds; while
     * it stays the same it is logged again every [STILL_LOG_MS] with how long, so a log shows the
     * search is still running (FBBD-271).
     */
    private fun note(text: String, shown: String) {
        tracker.searching(shown)
        val now = clock.nowMs()
        if (text != lastNote) {
            if (lastNote == null) searchingSinceMs = now
            lastNote = text
            lastNoteMs = now
            listener.log(text)
        } else if (now - lastNoteMs >= STILL_LOG_MS) {
            lastNoteMs = now
            listener.log("still searching after ${(now - searchingSinceMs) / MS_PER_S} s: $text")
        }
    }

    private inner class Poll(private val target: ObdTarget) : ObdListener {
        override fun ready(
            adapter: String,
            protocol: String,
            voltage: String?,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) {
            val clean = adapter.replace(Regex("\\s+"), " ")
            val pids = supported.joinToString(",") { hex(it, 2) }
            val polled = channels.joinToString(",") { it.id }
            listener.log("car answered: protocol $protocol, PIDs $pids, polling $polled")
            answered = true
            lostAtMs?.let { listener.log("car data back ${(clock.nowMs() - it) / MS_PER_S} s after the drop") }
            lostAtMs = null
            tracker.ready(clean, protocol, voltage, channels, clock.nowMs())
            listener.ready(target, clean, protocol, voltage, supported, channels)
        }

        override fun polling(channels: List<PidChannel>) {
            listener.log("polling now ${channels.joinToString(",") { it.id }}")
            tracker.polling(channels)
            listener.polling(target, channels)
        }

        override fun value(channel: PidChannel, value: Double, atMs: Long) {
            tracker.value(channel, value, atMs)
            listener.value(channel, value, atMs)
        }

        override fun log(text: String) = listener.log(text)

        override fun scanned(scan: CarScan) {
            val ids = scan.canIds.joinToString(" ") { "${hex(it.id, CanIdDigits)}:${it.frames}" }
            listener.log(
                "car scan: protocol ${scan.protocol}, PIDs ${scan.supported.size}, " +
                    "can ${if (scan.canHeard) "${scan.canIds.size} ids $ids".trimEnd() else "not heard"}",
            )
            tracker.scanned(scan)
            listener.scanned(target, scan)
        }
    }

    companion object {
        /** Retry after an ended connection: 5 s; while no adapter answers: 5 s, 10 s, then every 20 s. */
        val RETRY_MS = listOf(5_000L, 10_000L, 20_000L)

        /** Retry after a live connection was lost mid-drive (FBBD-271)... */
        const val LOST_RETRY_MS = 2_000L

        /** ...for this long after the drop; then [RETRY_MS] again, as for an adapter that is gone. */
        const val LOST_FAST_MS = 120_000L

        /**
         * Whether a connection the car answered on ended by the link, not the car: the link dropped or
         * a write failed, or the adapter went silent. "car stopped answering" is the engine switched off
         * (expected at a stop), and stopping or a restart is no loss.
         */
        internal fun isLinkLoss(reason: String) =
            reason.startsWith("connection lost") && !reason.endsWith(": closed") && !reason.endsWith(": interrupted") ||
                reason == "adapter stopped answering"

        /** How often a wait checks for [retryNow] and stopping. */
        const val WAIT_STEP_MS = 250L

        /** How often an unchanged "no adapter" is logged again. */
        const val STILL_LOG_MS = 60_000L
        private const val MS_PER_S = 1_000L
        private const val CanIdDigits = 3
    }
}

/**
 * Bluetooth names of OBD-II adapters (FBBD-121), so a paired one is used without being chosen:
 * generic ELM327 clones ("OBDII", "OBD2", "ELM327"), Vgate ("vLinker MC+", "V-LINK", "iCar"),
 * OBDLink, Konnwei (also as KW902/KW903), Viecar, Veepeak, Carista, PLX Kiwi, UniCarScan, Tonwon and
 * LELink. A Bluetooth LE adapter with another name counts when it advertises an ELM327 serial service
 * ([BleSerial.advertisesSerial]).
 */
object ObdAdapterNames {
    private val Parts = listOf(
        "obd", "elm327", "vlink", "v-link", "vgate", "icar", "konnwei", "viecar", "veepeak", "carista",
        "kiwi", "unicarscan", "tonwon", "lelink", "kw90",
    )

    fun isCandidate(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return Parts.any { it in n }
    }

    /** As [isCandidate] by name, or by the [services] a Bluetooth LE scan saw it advertise. */
    fun isCandidate(name: String?, services: List<String>): Boolean =
        isCandidate(name) || BleSerial.advertisesSerial(services)
}
