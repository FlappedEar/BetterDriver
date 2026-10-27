package com.flappedear.betterdriver.recorder.log

actual fun logInfo(tag: String, message: String) {
    val sink = IosLog.sink
    if (sink == null) println("I/$tag: $message") else sink(tag, message)
}
