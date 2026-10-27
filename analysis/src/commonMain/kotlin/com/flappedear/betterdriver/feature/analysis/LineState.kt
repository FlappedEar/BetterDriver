package com.flappedear.betterdriver.feature.analysis

/** What the view shows: the plan while it is worked out, why there is none, or the view. */
sealed interface LineState {
    data object Reading : LineState

    data class Unavailable(val why: String) : LineState

    data class Ready(val ui: LineUi) : LineState
}
