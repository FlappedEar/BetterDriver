package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo

enum class SessionState {
    /** A process holds the session lock and is writing to it. */
    RECORDING,
    /** Stopped cleanly. */
    FINISHED,
    /** Not stopped cleanly and not yet recovered (a crash, kill or power loss). */
    INTERRUPTED,
    /** Interrupted, then recovered. Exports like a finished session. */
    RECOVERED,
}

data class RecoveryReport(
    val id: String,
    val chunks: Int,
    val records: Long,
    val sampleSets: Long,
    val lastSampleMicros: Long?,
    /** Bytes of incomplete frames at chunk ends (dropped). */
    val truncatedBytes: Long,
    /** Bytes of damaged frames inside chunks (skipped). */
    val skippedBytes: Long,
)

/** Everything read back from a session. */
data class SessionData(
    val id: String,
    val info: SessionInfo,
    val state: SessionState,
    val channels: Map<Int, ChannelDef>,
    val sampleSets: List<SampleSet>,
    val events: List<RecorderEvent>,
    val end: Record.End?,
    val truncatedBytes: Long,
    val skippedBytes: Long,
)
