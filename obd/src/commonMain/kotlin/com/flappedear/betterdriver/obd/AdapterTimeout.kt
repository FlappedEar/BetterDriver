package com.flappedear.betterdriver.obd

/**
 * The ELM327's wait for more answers (`AT ST`, FBBD-284), shortened when requests stay slow: an
 * adapter that takes no response count, like Arek's Wi-Fi clone (latency_ms 226-241 with the count
 * "on"), waits out its timeout, about 200 ms, after the one ECU has answered. Each of [STEPS] is
 * tried for [TRIAL] requests; when one of them gets fewer values than the request got before, the
 * step before is put back and kept. [command] sends an AT command and returns its answer; [log] is
 * the app log.
 *
 * A request that answered before and then gets nothing [BACK_AFTER_MISSES] times in a row while the
 * wait is shortened puts the adapter's own wait back for the connection ([back], FBBD-382): on
 * 2026-10-29 the Wi-Fi clone's temperatures stopped a minute after the wait went to 0F.
 */
internal class AdapterTimeout(private val command: (String) -> String?, private val log: (String) -> Unit) {
    /** The [STEPS] step in use (-1: the adapter's own), requests left in its trial, done. */
    private var step = -1
    private var left = 0
    private var warmup = 0
    private var done = false

    /** A new connection starts at the adapter's own timeout; only CAN answers are timed by [STEPS]. */
    fun reset(can: Boolean) {
        step = -1
        left = 0
        warmup = 0
        done = !can
    }

    /**
     * After a request that got [got] values where it got [want] before (null when not known yet);
     * [latencyMs] is the scheduler's smoothed latency, and nothing is tried while it is under
     * [TUNE_ABOVE_MS].
     */
    @Suppress("ReturnCount") // Each early return is one state of the tuning.
    fun tune(want: Int?, got: Int, latencyMs: Double?) {
        if (done) return
        if (left > 0) {
            if (want != null && got < want) {
                val back = STEPS.getOrNull(step - 1) ?: DEFAULT
                command("ATST" + hex(back, 2))
                log("timeout ATST${hex(STEPS[step], 2)} lost an answer: back to ATST${hex(back, 2)}")
                done = true
                return
            }
            if (--left == 0) log("timeout ATST${hex(STEPS[step], 2)} kept")
            return
        }
        if (step < 0) {
            val slow = latencyMs != null && latencyMs >= TUNE_ABOVE_MS
            if (!slow || ++warmup < TRIAL) return
        }
        val next = step + 1
        if (next >= STEPS.size) {
            done = true
            return
        }
        if (command("ATST" + hex(STEPS[next], 2))?.contains("OK") != true) {
            log("timeout: ATST${hex(STEPS[next], 2)} not taken")
            done = true
            return
        }
        step = next
        left = TRIAL
    }

    /** [names] stopped answering: when the wait is shortened, the adapter's own goes back and stays. */
    fun back(names: String) {
        if (step < 0) return
        command("ATST" + hex(DEFAULT, 2))
        log("timeout ATST${hex(STEPS[step], 2)}: $names stopped answering, back to ATST${hex(DEFAULT, 2)}")
        reset(can = false)
    }

    companion object {
        /**
         * `AT ST` values tried in turn, in units of 4.096 ms: about 102, 78 and 61 ms, each still
         * above the 50 ms an ECU has to answer a mode 01 request (SAE J1979 P2). 0x32 (205 ms) is
         * the ELM327's default, put back when the first step loses an answer.
         */
        val STEPS = listOf(0x19, 0x13, 0x0F)
        private const val DEFAULT = 0x32
        private const val TRIAL = 20
        private const val TUNE_ABOVE_MS = 120.0

        /** Misses in a row of a request that answered before that put the adapter's own wait back. */
        const val BACK_AFTER_MISSES = 2
    }
}
