package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus

/**
 * Copies a recording's events (receiver links, fixes, video files, coach, storage) and status into
 * the debug log (FBBD-146). Video sync events come every second per file and are left out.
 */
class LoggingListener(private val sessionId: String, detail: String) : RecorderListener {
    init {
        DebugLog.i(Tag, "$sessionId started: $detail")
    }

    override fun onEvent(event: RecorderEvent) {
        if (event.kind == EventKinds.VIDEO_SYNC) return
        DebugLog.i(Tag, "$sessionId t=${event.tMicros / MicrosPerMilli}ms ${event.kind} ${event.text}")
    }

    override fun onStatus(status: RecorderStatus) {
        when (status) {
            is RecorderStatus.Stopped -> {
                val level = if (status.error == null) DebugLog.INFO else DebugLog.ERROR
                DebugLog.log(level, Tag, "$sessionId stopped: ${status.reason}", status.error)
            }
            is RecorderStatus.StorageLow -> DebugLog.w(Tag, "$sessionId storage low: ${status.usableBytes} bytes")
        }
    }

    private companion object {
        const val Tag = "Recording"
        const val MicrosPerMilli = 1_000L
    }
}
