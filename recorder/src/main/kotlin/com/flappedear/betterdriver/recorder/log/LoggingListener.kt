package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus

/**
 * Copies a recording's start, events (receiver links, fixes, video files and sync, coach, storage)
 * and end into the continuous log (FBBD-146, FBBD-183), each with the session's id and its session
 * time, so the hardware checks can be worked out from the log alone ([CheckLog]). Events the UI
 * process logs itself ([CheckLog.UiEventKinds]) are not logged again. [now] is the session clock.
 */
class LoggingListener(
    private val sessionId: String,
    detail: String,
    startUtcMillis: Long? = null,
    private val now: () -> Long = { 0L },
) : RecorderListener {
    init {
        DebugLog.i(Tag, "$sessionId started: $detail")
        val utc = startUtcMillis?.let { " utc_ms=$it" }.orEmpty()
        DebugLog.forSession(sessionId, CheckLog.SESSION, "start$utc source=${detail.replace(' ', '_')}")
    }

    override fun onEvent(event: RecorderEvent) {
        if (event.kind in CheckLog.UiEventKinds) return
        CheckLog.event(sessionId, event.tMicros, event.kind, event.text)
    }

    override fun onStatus(status: RecorderStatus) {
        when (status) {
            is RecorderStatus.Stopped -> {
                val level = if (status.error == null) DebugLog.INFO else DebugLog.ERROR
                DebugLog.log(level, Tag, "$sessionId stopped: ${status.reason}", status.error, sessionId)
                val reason = status.reason.replace(' ', '_')
                DebugLog.forSession(sessionId, CheckLog.SESSION, "stop t_us=${now()} reason=$reason")
            }
            is RecorderStatus.StorageLow -> DebugLog.w(Tag, "$sessionId storage low: ${status.usableBytes} bytes")
        }
    }

    private companion object {
        const val Tag = "Recording"
    }
}
