package com.flappedear.betterdriver.video

/**
 * A file could not be read or is damaged: java.io.IOException on the JVM, so the Android code that
 * handles IOException handles it as before the readers were common.
 */
expect open class IoProblem(message: String?, cause: Throwable?) : Exception
