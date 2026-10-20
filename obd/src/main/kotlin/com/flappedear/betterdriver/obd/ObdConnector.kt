package com.flappedear.betterdriver.obd

import java.io.IOException

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

    /** [channel] read [value] at [atMs] on the connector's clock. */
    fun value(channel: PidChannel, value: Double, atMs: Long) {}

    /** A connection to [target] that opened has ended, with [reason]. */
    fun ended(target: ObdTarget, reason: String) {}

    /** A line for the app log. */
    fun log(text: String) {}
}

/**
 * Keeps an OBD-II adapter connected (FBBD-15, FBBD-121), whatever carries it. Each round asks
 * [targets] for the adapters to try, in order (the app puts paired Bluetooth adapters before the
 * Wi-Fi one), and runs an [ElmPoller] on the first that opens. Only when none of them opens is
 * [fallback] asked (the app scans for a Bluetooth LE adapter there, which takes a few seconds).
 * An ended connection is retried soon; while no adapter answers the wait grows to [retryMs]'s last.
 *
 * [run] blocks until [running] turns false; [stop] closes the open connection so it returns. The
 * [tracker] follows every step for the OBD page. Pure JVM, so the whole loop is tested against
 * simulated adapters.
 */
@Suppress("LongParameterList") // Collaborators, each replaced in the tests.
class ObdConnector(
    private val targets: () -> List<ObdTarget>,
    private val listener: ObdConnectorListener,
    private val tracker: ObdStatusTracker,
    private val clock: ObdClock,
    private val profile: CarProfile = CarProfile.Generic,
    private val fallback: () -> List<ObdTarget> = { emptyList() },
    private val retryMs: List<Long> = RETRY_MS,
) {
    @Volatile
    private var running: () -> Boolean = { false }

    @Volatile
    private var open: ObdConnection? = null

    private var lastNote: String? = null

    fun run(running: () -> Boolean) {
        this.running = running
        var failures = 0
        while (running()) {
            val reason = try {
                connectOnce()
            } catch (e: IOException) {
                "connection lost: ${e.message}"
            } catch (e: SecurityException) {
                "no permission: ${e.message}"
            }
            if (!running()) break
            // A connection that opened and ended is retried soon; no adapter at all backs off.
            failures = if (reason == null) failures + 1 else 0
            clock.sleep(retryMs[(failures - 1).coerceIn(0, retryMs.size - 1)])
        }
    }

    /** Closes the open connection, so a blocked [run] notices [running] has turned false. */
    fun stop() {
        open?.close()
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
        listener.log("connected to ${target.where} over ${target.transport.label}")
        tracker.connecting(target.name)
        val reason = try {
            ElmPoller(line, profile, Poll(target), running, clock).run()
        } catch (e: IOException) {
            // The link dropped or a write failed: the usual way a connection ends.
            "connection lost: ${e.message}"
        } finally {
            open?.close()
            open = null
        }
        listener.log("connection to ${target.where} ended: $reason")
        tracker.ended(reason)
        listener.ended(target, reason)
        return reason
    }

    /** Opens the first of [all] that connects, with its line; adds why each other one failed to [errors]. */
    private fun openFirst(all: List<ObdTarget>, errors: MutableList<String>): Pair<ObdTarget, ElmLine>? =
        all.asSequence().takeWhile { running() }.firstNotNullOfOrNull { t -> tryOpen(t, errors)?.let { t to it } }

    /** The line to [t] once it opened, or null after adding why it did not to [errors]. */
    @Suppress("ReturnCount") // Opened, or one of two ways it failed.
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
        } catch (e: SecurityException) {
            errors += "${t.where} no permission: ${e.message}"
            return null
        }
        open = connection
        if (!running()) connection.close()
        return line
    }

    /** Logs [text] once until it changes, so a missing adapter is not logged every few seconds. */
    private fun note(text: String, shown: String) {
        tracker.searching(shown)
        if (text == lastNote) return
        lastNote = text
        listener.log(text)
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
            val pids = supported.joinToString(",") { "%02X".format(it) }
            val polled = channels.joinToString(",") { it.id }
            listener.log("car answered: protocol $protocol, PIDs $pids, polling $polled")
            tracker.ready(clean, protocol, voltage, channels, clock.nowMs())
            listener.ready(target, clean, protocol, voltage, supported, channels)
        }

        override fun value(channel: PidChannel, value: Double, atMs: Long) {
            tracker.value(channel, value, atMs)
            listener.value(channel, value, atMs)
        }

        override fun log(text: String) = listener.log(text)
    }

    companion object {
        /** Retry after an ended connection: 5 s; while no adapter answers: 5 s, 10 s, then every 20 s. */
        val RETRY_MS = listOf(5_000L, 10_000L, 20_000L)
    }
}

/**
 * Bluetooth names of OBD-II adapters (FBBD-121), so a paired one is used without being chosen:
 * generic ELM327 clones ("OBDII", "OBD2", "ELM327"), Vgate ("vLinker MC+", "V-LINK", "iCar"),
 * OBDLink, Konnwei, Viecar, Veepeak, Carista, PLX Kiwi, UniCarScan and Tonwon.
 */
object ObdAdapterNames {
    private val Parts = listOf(
        "obd", "elm327", "vlink", "v-link", "vgate", "icar", "konnwei", "viecar", "veepeak", "carista",
        "kiwi", "unicarscan", "tonwon",
    )

    fun isCandidate(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return Parts.any { it in n }
    }
}
