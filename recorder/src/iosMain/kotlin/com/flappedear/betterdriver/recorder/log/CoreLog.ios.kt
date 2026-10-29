package com.flappedear.betterdriver.recorder.log

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

actual fun logInfo(tag: String, message: String) = logLine(CoreLog.LevelInfo, tag, message, null, null)

actual fun logLine(level: Int, tag: String, message: String, error: Throwable?, session: String?) {
    val text = if (error == null) message else message + "\n" + error.stackTraceToString().trimEnd()
    val lines = IosLog.lines
    val sink = IosLog.sink
    when {
        lines != null -> lines(CoreLog.levelChar(level).toString(), tag, text, session)
        sink != null -> sink(tag, text)
        else -> println("${CoreLog.levelChar(level)}/$tag: $text")
    }
}

actual fun fixedDecimals(value: Double, digits: Int): String {
    if (!value.isFinite()) return value.toString()
    val scale = 10.0.pow(digits)
    val scaled = (abs(value) * scale).roundToLong()
    val unit = scale.toLong()
    val whole = scaled / unit
    val fraction = (scaled % unit).toString().padStart(digits, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (digits == 0) "$sign$whole" else "$sign$whole.$fraction"
}
