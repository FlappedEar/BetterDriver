package com.flappedear.betterdriver.debug

import androidx.activity.ComponentActivity

/**
 * The release twin of the debug build's responsiveness guardrails (FBBD-41): the same calls, doing
 * nothing. Release builds have no StrictMode policies, no JankStats and no jank lines in the log.
 */
@Suppress("UNUSED_PARAMETER", "UnusedParameter")
object DebugGuards {
    fun install(process: String) = Unit

    inline fun <T> allowDisk(block: () -> T): T = block()

    fun trackJank(activity: ComponentActivity, recording: Boolean) = Unit
}
