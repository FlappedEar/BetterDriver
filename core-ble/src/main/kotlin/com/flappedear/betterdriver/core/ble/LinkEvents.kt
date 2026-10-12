package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.log.DebugLog

/**
 * The `onState` of a source's link: writes [connected] (with [address]) when data starts flowing,
 * [disconnected] (with the reason) when a streaming link drops, and calls [reset] on every
 * reconnect so the decoder forgets partial data. A link calls `onState` one call at a time, so the
 * returned function needs no lock.
 */
fun linkEvents(
    sink: SampleSink,
    address: String,
    connected: String,
    disconnected: String,
    reset: () -> Unit,
): (LinkState) -> Unit {
    var wasStreaming = false
    return { s ->
        logLink(address, s, wasStreaming)
        val streaming = s is LinkState.Streaming
        if (streaming && !wasStreaming) sink.event(connected, address)
        if (s is LinkState.Reconnecting) {
            if (wasStreaming) sink.event(disconnected, s.reason)
            reset()
        }
        if (streaming || s is LinkState.Reconnecting) wasStreaming = streaming
    }
}

/**
 * Writes a link's state to the debug log (FBBD-146): every change except the message rate of a
 * link that keeps streaming, which updates every second.
 */
fun logLink(address: String, s: LinkState, wasStreaming: Boolean) {
    if (s is LinkState.Streaming && wasStreaming) return
    val level = if (s is LinkState.Reconnecting) DebugLog.WARN else DebugLog.INFO
    DebugLog.log(level, "Link", "$address $s", null)
}
