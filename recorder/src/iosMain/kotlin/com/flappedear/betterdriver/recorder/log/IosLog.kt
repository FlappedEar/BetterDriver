package com.flappedear.betterdriver.recorder.log

import kotlin.concurrent.Volatile

/**
 * Where common code's log lines go on iOS: the app sets [lines] to its continuous log (AppLog.swift)
 * at launch, with each line's level letter ("I", "W", ...) and the recording it names (null: the one
 * being recorded). [sink] is the older form without them. Until one is set, and in tests, lines are
 * printed.
 */
object IosLog {
    @Volatile
    var sink: ((tag: String, message: String) -> Unit)? = null

    @Volatile
    var lines: ((level: String, tag: String, message: String, session: String?) -> Unit)? = null
}
