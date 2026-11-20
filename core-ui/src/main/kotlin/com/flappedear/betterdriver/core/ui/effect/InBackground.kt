package com.flappedear.betterdriver.core.ui.effect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Runs [block] in [context] (Dispatchers.IO or Default) and comes back to the main thread with its
 * result, for a composition's effects: LaunchedEffect, produceState, rememberCoroutineScope.
 *
 * In the app a plain withContext already comes back to main, because those effects run on the main
 * dispatcher. The Compose test rule runs them on an unconfined test dispatcher instead, so after a
 * plain withContext the effect carried on in the worker thread, wrote its state there and sent the
 * snapshot changes from there. Compose then used the screen's snapshot observer on the worker while
 * the main thread drew with it: "Detected multithreaded access to SnapshotStateObserver" and
 * "Must run runDetachLifecycle() once" in the emulator smoke tests (FBBD-485). Main.immediate costs
 * nothing in the app, where the effect is on main already. The rest of [context] (NonCancellable, say)
 * applies to the way back too, so a result made under NonCancellable still reaches the caller.
 */
suspend fun <T> inBackground(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T =
    withContext(context.minusKey(ContinuationInterceptor) + Dispatchers.Main.immediate) { withContext(context, block) }
