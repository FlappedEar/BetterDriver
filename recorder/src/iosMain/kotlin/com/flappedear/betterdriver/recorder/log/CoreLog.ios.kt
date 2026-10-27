package com.flappedear.betterdriver.recorder.log

actual fun logInfo(tag: String, message: String) = println("I/$tag: $message")
