package com.flappedear.betterdriver.recorder

/**
 * Live consumers (track screen, live engine, cue engine). On Android they run on their own thread,
 * every call is wrapped, and the recording never waits for them or stops because of them (FBBD-69).
 */
interface RecorderListener {
    fun onChannel(def: ChannelDef) = Unit
    fun onSamples(set: SampleSet) = Unit
    fun onEvent(event: RecorderEvent) = Unit
    fun onStatus(status: RecorderStatus) = Unit
}

sealed interface RecorderStatus {
    data class StorageLow(val usableBytes: Long) : RecorderStatus
    data class Stopped(val reason: String, val error: Throwable? = null) : RecorderStatus
}
