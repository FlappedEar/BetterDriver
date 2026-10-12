package com.flappedear.betterdriver.recording

import android.content.Context

/** The release twin of the debug build's Demo GNSS switch for emulator tests: Demo GNSS is always on. */
@Suppress("UNUSED_PARAMETER", "UnusedParameter")
object DemoGnssHook {
    const val FileName = "demo-gnss-off"

    fun receiverOn(context: Context): () -> Boolean = { true }
}
