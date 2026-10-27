package com.flappedear.betterdriver.ios

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

/**
 * Hands an uncaught Kotlin exception to the app's log before the app ends (the iOS side of Android's
 * crash handler in AppLog.kt): [onCrash] gets the exception's stack trace, then Kotlin ends the
 * process as it would have.
 */
object KotlinCrashLog {
    @OptIn(ExperimentalNativeApi::class)
    fun install(onCrash: (String) -> Unit) {
        setUnhandledExceptionHook { error -> onCrash(error.stackTraceToString()) }
    }
}
