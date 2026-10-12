package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicInteger

/**
 * The headset delay test's click (FBBD-49), played on the coach voice's own output path ([AudioOut]):
 * the same audio attributes and focus, to the active output, or forced to the phone speaker for the
 * reaction baseline. [close] when the test screen goes.
 */
internal class ClickPlayer(context: Context) {
    private val thread = HandlerThread("delay-test").apply { start() }
    private val out = AudioOut(context, Handler(thread.looper))
    private val click = Pcm.click(SampleRate)
    private val ids = AtomicInteger()

    /** Plays the click; when play was asked for in [android.os.SystemClock.uptimeMillis], or null if it could not. */
    fun play(speaker: Boolean): Long? = out.play(click, "click-${ids.incrementAndGet()}", speaker)

    /** The headset connected, by name, or null if sound goes to the phone. */
    fun headset(): String? = out.headset()

    fun close() {
        out.shutdown()
        thread.quitSafely()
    }

    private companion object {
        const val SampleRate = 48_000
    }
}
