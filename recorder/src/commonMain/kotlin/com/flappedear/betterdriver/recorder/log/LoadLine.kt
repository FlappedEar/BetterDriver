package com.flappedear.betterdriver.recorder.log

/**
 * What one process had used up to one moment (FBBD-281): its CPU time, its garbage collections and
 * the bytes it allocated since it started, and its heap and threads then. Two samples a minute
 * apart make a [LoadLine].
 */
data class LoadSample(
    val uptimeMillis: Long,
    val cpuMillis: Long,
    val gcCount: Long,
    val gcMillis: Long,
    val allocatedBytes: Long,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val threads: Int,
)

/**
 * The work one process did between two [LoadSample]s, logged once a minute in every process while a
 * session records (FBBD-281). Arek, 2026-10-21: "we are losing performance without huge amount of new
 * features"; no log could say how much each process works, so each says so here:
 *
 * `load: cpu_pct=12.3 cpu_ms=7380 wall_ms=60000 gc=3 gc_ms=45 alloc_mb=12.4 heap_mb=34/512 threads=41`
 *
 * [cpuPercent] is of one core: a process busy on two cores for the whole minute shows 200.
 */
data class LoadLine(
    val cpuPercent: Double,
    val cpuMillis: Long,
    val wallMillis: Long,
    val gcCount: Long,
    val gcMillis: Long,
    val allocatedMb: Double,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val threads: Int,
) {
    fun text(): String =
        "$Prefix cpu_pct=${fixedDecimals(cpuPercent, 1)} cpu_ms=$cpuMillis wall_ms=$wallMillis gc=$gcCount " +
            "gc_ms=$gcMillis alloc_mb=${fixedDecimals(allocatedMb, 1)} heap_mb=$heapUsedMb/$heapMaxMb threads=$threads"

    companion object {
        /** The log tag of these lines. */
        const val TAG = "Load"
        private const val Prefix = "load:"
        private const val Percent = 100.0
        private const val BytesPerMb = 1L shl 20

        /** The work between [from] and [to]; null when no time passed between them. */
        fun between(from: LoadSample, to: LoadSample): LoadLine? {
            val wall = to.uptimeMillis - from.uptimeMillis
            if (wall <= 0) return null
            val cpu = (to.cpuMillis - from.cpuMillis).coerceAtLeast(0)
            return LoadLine(
                cpuPercent = cpu * Percent / wall,
                cpuMillis = cpu,
                wallMillis = wall,
                gcCount = (to.gcCount - from.gcCount).coerceAtLeast(0),
                gcMillis = (to.gcMillis - from.gcMillis).coerceAtLeast(0),
                allocatedMb = (to.allocatedBytes - from.allocatedBytes).coerceAtLeast(0).toDouble() / BytesPerMb,
                heapUsedMb = to.heapUsedBytes / BytesPerMb,
                heapMaxMb = to.heapMaxBytes / BytesPerMb,
                threads = to.threads,
            )
        }

        /** A logged line's message as a [LoadLine]; null for any other message. */
        fun parse(message: String): LoadLine? {
            val f = CheckLog.fields(message.removePrefix(Prefix))
            if (!message.startsWith(Prefix) || Required.any { f[it]?.toDoubleOrNull() == null }) return null
            val heap = f["heap_mb"]?.split('/')
            return LoadLine(
                cpuPercent = f.getValue("cpu_pct").toDouble(),
                cpuMillis = f.getValue("cpu_ms").toDouble().toLong(),
                wallMillis = f.getValue("wall_ms").toDouble().toLong(),
                gcCount = f["gc"]?.toLongOrNull() ?: 0,
                gcMillis = f["gc_ms"]?.toLongOrNull() ?: 0,
                allocatedMb = f["alloc_mb"]?.toDoubleOrNull() ?: 0.0,
                heapUsedMb = heap?.getOrNull(0)?.toLongOrNull() ?: 0,
                heapMaxMb = heap?.getOrNull(1)?.toLongOrNull() ?: 0,
                threads = f["threads"]?.toIntOrNull() ?: 0,
            )
        }

        /** The fields a line cannot be read without. */
        private val Required = listOf("cpu_pct", "cpu_ms", "wall_ms")
    }
}
