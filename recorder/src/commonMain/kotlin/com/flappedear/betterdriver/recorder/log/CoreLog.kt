package com.flappedear.betterdriver.recorder.log

/**
 * An information line from common code, tagged as DebugLog's: on the JVM it goes to [DebugLog], so
 * the Android app's continuous log keeps it; iOS prints it.
 */
expect fun logInfo(tag: String, message: String)
