package com.flappedear.betterdriver.recorder.log

import kotlin.concurrent.Volatile

/**
 * Where common code's log lines go on iOS: the app sets [sink] to its continuous log (AppLog.swift)
 * at launch; until then, and in tests, lines are printed.
 */
object IosLog {
    @Volatile
    var sink: ((tag: String, message: String) -> Unit)? = null
}
