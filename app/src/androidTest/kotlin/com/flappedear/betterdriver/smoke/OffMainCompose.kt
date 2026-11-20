package com.flappedear.betterdriver.smoke

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Fails a smoke test when a thread other than main used the snapshot observer of one of the app's
 * Compose views during it (FBBD-485), and names that thread with its stack at that moment.
 *
 * "Detected multithreaded access to SnapshotStateObserver" and the NPE in its recordRead failed a
 * different smoke test now and then (FBBD-208, FBBD-422, FBBD-485). That error only shows the main
 * thread's stack, and the other thread has moved on by the time the test fails. So a thread watches
 * the observer itself: Compose keeps the id of the thread inside it (layout, draw, semantics) in a
 * private field, read here by reflection every [PollMicros]. A thread other than main found there
 * is caught in the act.
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
            val seen = watch.report()
            failure?.let { f ->
                seen?.let { f.addSuppressed(AssertionError(it)) }
                throw f
            }
            seen?.let { throw AssertionError(it) }
        }
    }

    private class Watch : Thread("off-main-compose-watch") {
        private val running = AtomicBoolean(true)
        private val mainId = Looper.getMainLooper().thread.id

        /** The snapshot observers of the Compose views on screen, refreshed every [RefreshMs]. */
        private var observers: List<Any> = emptyList()
        private var refreshed = 0L

        /** Distinct stacks seen, each with the thread that ran it. */
        private val stacks = LinkedHashMap<String, String>()
        private val errors = mutableListOf<String>()


        init {
            isDaemon = true
        }

        @Suppress("TooGenericExceptionCaught") // A watcher that stops on a view race would see nothing.
        override fun run() {
            while (running.get()) {
                try {
                    poll()
                } catch (e: Exception) {
                    if (errors.size < MaxStacks) errors += e.toString()
                }
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(PollMicros))
            }
        }

        private fun poll() {
            val now = System.nanoTime()
            if (now - refreshed > TimeUnit.MILLISECONDS.toNanos(RefreshMs)) {
                val views = composeViews()
                observers = views.mapNotNull(::observerOf)
                // A Compose view whose observer is not found would leave this watch blind.
                if (views.isNotEmpty() && observers.isEmpty() && errors.size < MaxStacks) {
                    errors += "no snapshot observer found on ${views.first().javaClass.name}"
                }
                refreshed = now
            }
            for (observer in observers) {
                val id = threadIdField.getLong(observer)
                if (id != -1L && id != mainId) caught(id)
            }
        }

        private fun caught(id: Long) {
            val thread = getAllStackTraces().keys.firstOrNull { it.id == id } ?: return
            val stack = thread.stackTrace.take(MaxFrames).joinToString("\n") { "    at $it" }
            synchronized(stacks) {
                if (stacks.size < MaxStacks && stack !in stacks) stacks[stack] = "${thread.name} (id $id)"
            }
        }

        fun finish() {
            running.set(false)
            join(TimeUnit.SECONDS.toMillis(1))
        }

        /** What was seen, or null when nothing was. */
        fun report(): String? {
            val lines = mutableListOf<String>()
            synchronized(stacks) {
                stacks.forEach { (stack, thread) -> lines += "Compose snapshot observer used on $thread:\n$stack" }
            }
            if (errors.isNotEmpty()) lines += "Watch errors: ${errors.joinToString()}"
            return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "Off the main thread (FBBD-485):\n")
        }
    }

    private companion object {
        const val PollMicros = 200L
        const val RefreshMs = 100L
        const val MaxFrames = 60
        const val MaxStacks = 5

        /** OwnerSnapshotObserver.observer, then SnapshotStateObserver.currentMapThreadId. */
        val ownerObserverField: Field by lazy {
            Class.forName("androidx.compose.ui.node.OwnerSnapshotObserver")
                .getDeclaredField("observer").apply { isAccessible = true }
        }
        val threadIdField: Field by lazy {
            Class.forName("androidx.compose.runtime.snapshots.SnapshotStateObserver")
                .getDeclaredField("currentMapThreadId").apply { isAccessible = true }
        }

        /** The view's SnapshotStateObserver, through AndroidComposeView.getSnapshotObserver(). */
        fun observerOf(view: View): Any? {
            // Owner is internal to Compose, so the getter may carry a "$ui_release" suffix.
            val owner = view.javaClass.methods.firstOrNull {
                it.name.startsWith("getSnapshotObserver") && it.parameterCount == 0
            }
            return owner?.invoke(view)?.let { ownerObserverField.get(it) }
        }

        /** Every Compose view in the app's windows (dialogs and popups are windows of their own). */
        fun composeViews(): List<View> {
            val found = mutableListOf<View>()
            fun visit(view: View) {
                if (view is ViewRootForTest) {
                    found += view.view
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) view.getChildAt(i)?.let(::visit)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WindowInspector.getGlobalWindowViews().forEach(::visit)
            return found
        }
    }
}
