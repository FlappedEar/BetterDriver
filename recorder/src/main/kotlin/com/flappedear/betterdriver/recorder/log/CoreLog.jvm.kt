package com.flappedear.betterdriver.recorder.log

import java.util.Locale

actual fun logInfo(tag: String, message: String) = DebugLog.i(tag, message)

actual fun logLine(level: Int, tag: String, message: String, error: Throwable?, session: String?) =
    DebugLog.log(level, tag, message, error, session ?: DebugLog.session)

actual fun fixedDecimals(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)
