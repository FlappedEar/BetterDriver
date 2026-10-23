package com.flappedear.betterdriver.canreader

import com.flappedear.betterdriver.obd.CanMonitor
import com.flappedear.betterdriver.obd.ElmLine

/** One CAN frame at the OBD port, stamped on arrival. */
data class Frame(val atMs: Long, val id: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.atMs == atMs && other.id == id && other.data.contentEquals(data)

    override fun hashCode(): Int = (atMs.hashCode() * 31 + id) * 31 + data.contentHashCode()
}

/**
 * The reader's CAN steps (FBBD-319), as the main app's probe does them (FBBD-263): `AT CAF0` so
 * frames show as sent, `AT CRA <id>` to keep one id, `AT MA` to monitor, any byte to stop it. Each
 * frame goes to the log as `frame <ms> <id> <data>` with when it arrived at the phone.
 */
class CanSteps(
    private val line: ElmLine,
    private val tap: FrameTap,
    private val log: ReaderLog,
    private val running: () -> Boolean,
    private val config: ReaderConfig,
) {
    /** Every id on the bus for [ReaderConfig.surveyMs], with its frame count. */
    fun survey(): Map<Int, Int> {
        log.step("CAN survey: every id for ${config.surveyMs / MS_PER_S} s")
        enterMonitor()
        command("ATCRA")
        val frames = monitor(config.surveyMs)
        leaveMonitor()
        val counts = frames.groupingBy { it.id }.eachCount().toSortedMap()
        counts.forEach { (id, n) ->
            log.data("survey", "${hex3(id)} n=$n hz=${"%.1f".format(n * MS_PER_S / config.surveyMs)}")
        }
        log.summary("CAN survey: ${counts.size} ids: ${counts.keys.joinToString(" ") { hex3(it) }}")
        return counts
    }

    /** Each of [ids] on its own for [ReaderConfig.listenMs]: rate and which bytes change. */
    fun listenEach(ids: List<Int>) {
        log.step("CAN listen: ${ids.size} ids, ${config.listenMs} ms each")
        enterMonitor()
        for (id in ids) {
            if (!running()) break
            listen(id, config.listenMs, summary = true)
        }
        leaveMonitor()
    }

    /** Listens to [id] alone for [windowMs]; the number of its frames. Between [enterMonitor] and [leaveMonitor]. */
    fun listen(id: Int, windowMs: Long, summary: Boolean): Int {
        command("ATCRA" + hex3(id))
        val frames = monitor(windowMs).filter { it.id == id }
        if (summary) {
            val hz = frames.size * MS_PER_S / windowMs
            log.data("listen", "${hex3(id)} n=${frames.size} hz=${"%.1f".format(hz)} ${changes(frames)}")
        }
        return frames.size
    }

    /** Raw frames, no ISO-TP reading. */
    fun enterMonitor() {
        command("ATCAF0")
    }

    /** Back to requests: every id received, ISO-TP read again. */
    fun leaveMonitor() {
        if (command("ATCRA")?.contains("OK") != true) command("ATAR")
        command("ATCAF1")
    }

    private fun monitor(windowMs: Long): List<Frame> {
        tap.start()
        line.monitor("ATMA", windowMs, STOP_MS)
        val lines = tap.stop()
        val frames = lines.mapNotNull { l ->
            CanMonitor.frames(l.text).firstOrNull()?.let { (id, data) -> Frame(l.atMs, id, data) }
        }
        val other = lines.map { it.text }.filter { CanMonitor.frames(it).isEmpty() }.distinct()
        if (other.isNotEmpty()) {
            log.data("cmd", "ATMA also printed: ${other.take(MAX_OTHER).joinToString(" | ")}")
        }
        frames.forEach { f -> log.data("frame", "${f.atMs} ${hex3(f.id)} ${f.data.hex()}") }
        return frames
    }

    /** Per byte position of [frames]: how many values it took, and its range, for bytes that changed. */
    private fun changes(frames: List<Frame>): String {
        if (frames.isEmpty()) return "no frames"
        val width = frames.maxOf { it.data.size }
        val moving = (0 until width).mapNotNull { i ->
            val values = frames.mapNotNull { it.data.getOrNull(i)?.toInt()?.and(ByteMask) }
            val distinct = values.distinct()
            if (distinct.size < 2) null else "b$i:${distinct.size}vals %02X..%02X".format(values.min(), values.max())
        }
        val moved = moving.ifEmpty { listOf("constant") }.joinToString(" ")
        return "len=$width $moved last=${frames.last().data.hex()}"
    }

    private fun command(text: String): String? {
        val answer = line.command(text, FrsReader.AT_MS)
        log.data("cmd", "$text -> ${clean(answer)}")
        return answer
    }

    private companion object {
        const val STOP_MS = 1_000L
        const val MS_PER_S = 1_000.0
        const val MAX_OTHER = 5
    }
}

private const val ByteMask = 0xFF

private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and ByteMask) }
