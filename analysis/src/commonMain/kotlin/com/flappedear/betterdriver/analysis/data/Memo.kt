package com.flappedear.betterdriver.analysis.data

/** Results worked out once per session or channel, so the many readers of one recording share them. */
internal interface Memo<K : Any, V : Any> {
    /** The result for [key], worked out by [compute] when there is none yet. */
    fun getOrPut(key: K, compute: () -> V): V
}

/**
 * A [Memo] that holds its keys weakly, so a session's results go with the session. Safe to share
 * between threads. On the JVM a synchronized WeakHashMap; iOS keeps nothing and works each result
 * out again, which gives the same results.
 */
internal expect fun <K : Any, V : Any> weakMemo(): Memo<K, V>

/** A [Memo] that holds its keys, safe to share between threads; iOS keeps nothing, as [weakMemo]. */
internal expect fun <K : Any, V : Any> memo(): Memo<K, V>
