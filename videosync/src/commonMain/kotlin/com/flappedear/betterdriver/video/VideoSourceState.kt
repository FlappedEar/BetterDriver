package com.flappedear.betterdriver.video

/** What a live source is doing, for the UI. */
sealed interface VideoSourceState {
    data object Idle : VideoSourceState
    data object Starting : VideoSourceState
    data class Recording(val sinceSessionUs: Long) : VideoSourceState
    data object Stopping : VideoSourceState

    /** Stopped on its own. The session goes on; only this source's video is affected. */
    data class Failed(val reason: String) : VideoSourceState
}
