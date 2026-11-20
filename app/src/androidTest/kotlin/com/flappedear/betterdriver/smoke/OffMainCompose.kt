package com.flappedear.betterdriver.smoke

import android.os.Looper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fails a smoke test when a thread other than main ran the activity's Compose layout, draw or
 * semantics code during it (FBBD-485), and names that thread and its stack.
 *
 * "Detected multithreaded access to SnapshotStateObserver" and the NPE in its recordRead failed a
 * different smoke test now and then (FBBD-208, FBBD-422, FBBD-485). The main thread's stack is all
 * that error shows, and the other thread had moved on by then, so a thread samples every thread
 * while the test runs and keeps any non-main stack inside androidx.compose.ui.node (the owner's
 * layout nodes and its snapshot observer). What it finds is the bug the rare crash was a symptom of.
 */
class OffMainCompose : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        @Suppress("TooGenericExceptionCaught") // Rethrown below, with what was seen added.
        override fun evaluate() {
            val watch = Watch().apply { start() }
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (e: Throwable) {
                failure = e
            } finally {
                watch.finish()
            }
            val seen = watch.report(failure)
            failure?.let { f ->
                seen?.let { f.addSuppressed(AssertionError(it)) }
                throw f
            }
            seen?.let { throw AssertionError(it) }
        }
    }

    private class Watch : Thread("off-main-compose-watch") {
        private val running = AtomicBoolean(true)
        private val main = Looper.getMainLooper().thread

        /** Distinct stacks seen, each with the thread that ran it. */
        private val stacks = LinkedHashMap<String, String>()

        init {
            isDaemon = true
        }

        override fun run() {
            while (running.get()) {
                sample()
                sleep(SampleMs)
            }
        }

        private fun sample() {
            for ((thread, frames) in getAllStackTraces()) {
                if (thread === main || thread === this || frames.none(::isOwnerFrame)) continue
                val stack = frames.take(MaxFrames).joinToString("\n") { "    at $it" }
                synchronized(stacks) {
                    if (stacks.size < MaxStacks && stack !in stacks) {
                        stacks[stack] = "${thread.name} (id ${thread.id})"
                    }
                }
            }
        }

        fun finish() {
            running.set(false)
            join(SampleMs * 10)
        }

        /**
         * What was seen, or null when nothing was; with a "previousThreadId=N" failure, also the
         * thread N as it is now.
         */
        fun report(failure: Throwable?): String? {
            val lines = mutableListOf<String>()
            synchronized(stacks) {
                stacks.forEach { (stack, thread) -> lines += "Compose layout/draw/semantics on $thread:\n$stack" }
            }
            val id = failure?.let { Regex("previousThreadId=(\\d+)").find(it.toString()) }?.groupValues?.get(1)
            id?.toLongOrNull()?.let { n ->
                val other = getAllStackTraces().entries.firstOrNull { it.key.id == n }
                lines += other?.let { (t, frames) ->
                    "Thread $n now: ${t.name}, ${t.state}\n" +
                        frames.take(MaxFrames).joinToString("\n") { "    at $it" }
                } ?: "Thread $n no longer runs"
            }
            return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "Off the main thread (FBBD-485):\n")
        }
    }

    private companion object {
        const val SampleMs = 5L
        const val MaxFrames = 60
        const val MaxStacks = 5

        fun isOwnerFrame(frame: StackTraceElement): Boolean = frame.className.startsWith("androidx.compose.ui.node.")
    }
}
