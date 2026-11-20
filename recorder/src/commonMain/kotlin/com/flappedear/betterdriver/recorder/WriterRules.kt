package com.flappedear.betterdriver.recorder

private const val Mib = 1L shl 20
private const val DefaultFlushMs = 100L
private const val DefaultSyncMs = 500L
private const val DefaultChunkMib = 8L
private const val DefaultChunkAgeMs = 60_000L
private const val DefaultWarnMib = 200L
private const val DefaultStopMib = 50L
private const val DefaultStorageCheckMs = 2_000L
private const val DefaultQueueCapacity = 100_000
private const val DefaultListenerQueueCapacity = 1_024
private const val MaxSyncIntervalMs = 1_000L

/**
 * How a recording writes, on both platforms (FBBD-360): sources hand records to a write queue and
 * return, one writer puts them into chunk files, fsyncs them and watches free space.
 */
data class RecorderConfig(
    /** Written data is fsynced at least this often, so a power loss loses at most about this much (FBBD-70). */
    val syncIntervalMs: Long = DefaultSyncMs,
    /**
     * Android: written data is handed to the OS whenever the write queue is empty and at least this
     * often, so killing the process loses (almost) nothing; never later than the fsync. iOS writes
     * each batch straight away.
     */
    val flushIntervalMs: Long = minOf(DefaultFlushMs, syncIntervalMs),
    val chunkMaxBytes: Long = DefaultChunkMib * Mib,
    val chunkMaxAgeMs: Long = DefaultChunkAgeMs,
    /** Below this much free space the recorder warns. */
    val storageWarnBytes: Long = DefaultWarnMib * Mib,
    /** Below this much free space the recorder stops cleanly, keeping what it has. */
    val storageStopBytes: Long = DefaultStopMib * Mib,
    val storageCheckIntervalMs: Long = DefaultStorageCheckMs,
    /** Records waiting for the writer, at most; beyond it a record is counted as dropped. */
    val queueCapacity: Int = DefaultQueueCapacity,
    /** Android: records waiting for each live listener, at most. iOS hands records to its listeners directly. */
    val listenerQueueCapacity: Int = DefaultListenerQueueCapacity,
) {
    init {
        require(syncIntervalMs in 1..MaxSyncIntervalMs) {
            "syncIntervalMs must be 1..1000 to meet the 1 s loss limit"
        }
        require(flushIntervalMs in 1..syncIntervalMs) { "flushIntervalMs must be 1..syncIntervalMs" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    /** Whether a chunk this big and this old is closed and a new one opened before the next frame. */
    fun chunkDue(bytes: Long, ageMs: Long): Boolean = bytes >= chunkMaxBytes || ageMs >= chunkMaxAgeMs
}

/** Why a recording ended, besides the stop reason its caller gave ("user" and others). */
object EndReasons {
    /** Free space fell below [RecorderConfig.storageStopBytes]. */
    const val STORAGE_FULL = "storage_full"

    /** A write or fsync failed; the files are left for recovery. */
    const val ERROR = "error"
}

/** What one free-space check found (see [StorageGuard]). */
data class StorageCheck(
    val freeBytes: Long,
    /** First time below the warn line: log [EventKinds.STORAGE_LOW] and tell the screen, once. */
    val warn: Boolean,
    /**
     * Below the stop line: log [EventKinds.STORAGE_FULL], stop rotating chunks (from here on every
     * write adds exactly its own frame, as a rotation's channel table would eat the space kept for
     * the end record) and end the recording.
     */
    val stop: Boolean,
)

/**
 * The recorder's free-space rule, the same on both platforms (FBBD-360): checked at most every
 * [RecorderConfig.storageCheckIntervalMs] on the writer's clock, a warning once below
 * [RecorderConfig.storageWarnBytes], a stop below [RecorderConfig.storageStopBytes].
 * Not thread safe: the writer alone calls it.
 */
class StorageGuard(private val config: RecorderConfig) {
    private var lastCheckMs: Long? = null
    private var warned = false

    /** Null when no check is due at [nowMs]; otherwise reads [freeBytes] once and says what to do. */
    fun check(nowMs: Long, freeBytes: () -> Long): StorageCheck? {
        val last = lastCheckMs
        if (last != null && nowMs - last < config.storageCheckIntervalMs) return null
        lastCheckMs = nowMs
        val free = freeBytes()
        val warn = free < config.storageWarnBytes && !warned
        if (warn) warned = true
        return StorageCheck(free, warn, free < config.storageStopBytes)
    }
}

/**
 * Reports the write queue's drops as they grow (FBBD-350): [due] gives the count to log as an
 * [EventKinds.DROPPED_SAMPLES] event, or null when it has not changed since the last report.
 */
class DropReport {
    private var reported = 0L

    fun due(dropped: Long): Long? = if (dropped == reported) null else dropped.also { reported = it }
}
