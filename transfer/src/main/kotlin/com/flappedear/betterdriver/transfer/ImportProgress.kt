package com.flappedear.betterdriver.transfer

/**
 * How far an import of one or more files is (FBBD-314, UI-192 B, UI-193 A): bytes read against the
 * files' sizes, for the current file and for all of them, and the time left at the speed so far.
 *
 * [sizes] are the picked files' sizes in bytes, 0 where the provider does not say. Only time spent
 * reading counts towards the speed: a pause longer than [PauseNanos] between two blocks (a
 * keep-or-replace question, the track being stored) is left out. [clock] gives nanoseconds.
 * Used from one thread.
 */
class ImportProgress(private val sizes: List<Long>, private val clock: () -> Long = System::nanoTime) {

    /** One moment of the import. A size of 0 means unknown. */
    data class Snapshot(
        /** The current file, from 0. */
        val file: Int,
        val files: Int,
        val fileDone: Long,
        val fileSize: Long,
        /** Bytes of the files before this one (their whole size) plus this one's. */
        val totalDone: Long,
        /** All files' sizes; 0 when any of them is unknown. */
        val totalSize: Long,
        /** At the speed so far; null until there is enough to tell, or with no size to go by. */
        val secondsLeft: Long?,
    ) {
        /** This file's share read, or null when its size is unknown. */
        val fileFraction: Float? get() = if (fileSize > 0) share(fileDone, fileSize) else null

        /** All files' share read; by files done when a size is unknown. */
        val totalFraction: Float get() = if (totalSize > 0) share(totalDone, totalSize) else file.toFloat() / files
    }

    private val totalSize = if (sizes.all { it > 0 }) sizes.sum() else 0L
    private var file = 0
    private var before = 0L
    private var done = 0L
    private var readNanos = 0L
    private var readBytes = 0L
    private var lastBlock = Long.MIN_VALUE
    private var lastShown = Long.MIN_VALUE
    private var shownPermille = -1

    /** Starts file [index]; the files before it count as read, whether they came in or not. */
    fun start(index: Int) {
        require(index in sizes.indices) { "no file $index" }
        file = index
        before = sizes.take(index).sum()
        done = 0L
        lastBlock = Long.MIN_VALUE
        shownPermille = -1
    }

    /** [n] more bytes of the current file read. Returns whether the change is worth showing. */
    fun bytes(n: Int): Boolean {
        val now = clock()
        if (lastBlock != Long.MIN_VALUE && now - lastBlock <= PauseNanos) {
            readNanos += now - lastBlock
            readBytes += n
        }
        lastBlock = now
        done += n
        val size = sizes[file]
        val permille = if (size > 0) (done * Permille / size).coerceAtMost(Permille.toLong()).toInt() else -1
        val due = lastShown == Long.MIN_VALUE || now - lastShown >= ShowEveryNanos
        if (permille == shownPermille && !due) return false
        shownPermille = permille
        lastShown = now
        return true
    }

    fun snapshot(): Snapshot {
        val size = sizes[file]
        val fileDone = if (size > 0) done.coerceAtMost(size) else done
        val totalDone = (before + fileDone).let { if (totalSize > 0) it.coerceAtMost(totalSize) else it }
        val left = when {
            totalSize > 0 -> totalSize - totalDone
            size > 0 -> size - fileDone
            else -> null
        }
        val secondsLeft = if (left != null && readNanos >= MinReadNanos && readBytes > 0) {
            (left.toDouble() * readNanos / readBytes / NanosPerSecond).toLong()
        } else {
            null
        }
        return Snapshot(file, sizes.size, fileDone, size, totalDone, totalSize, secondsLeft)
    }

    companion object {
        private const val Permille = 1000
        private const val NanosPerSecond = 1_000_000_000L
        private const val PauseNanos = 2 * NanosPerSecond
        private const val MinReadNanos = 2 * NanosPerSecond
        private const val ShowEveryNanos = NanosPerSecond / 2
    }
}

private fun share(done: Long, size: Long): Float = (done.toDouble() / size).toFloat().coerceIn(0f, 1f)

/**
 * The order several picked files are imported in (UI-193 A): oldest session first, as the line
 * analysis of a day builds on the sessions before. A session's time is read from its file name
 * ("session_20260829_100009…", the app's own and RaceChrono's names); files without one follow in
 * the order picked. Returns indexes into [names].
 */
object ImportOrder {
    private val Stamp = Regex("""(\d{8})[_-](\d{6})""")

    fun oldestFirst(names: List<String>): List<Int> {
        val stamps = names.map { n -> Stamp.find(n)?.let { it.groupValues[1] + it.groupValues[2] } }
        val stamped = names.indices.filter { stamps[it] != null }.sortedBy { stamps[it] }
        return stamped + names.indices.filter { stamps[it] == null }
    }
}
