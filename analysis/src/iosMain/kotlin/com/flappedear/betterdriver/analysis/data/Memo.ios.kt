package com.flappedear.betterdriver.analysis.data

// Kotlin/Native has no WeakHashMap, and a session is analysed once on the iPad, so nothing is kept.
private class NoMemo<K : Any, V : Any> : Memo<K, V> {
    override fun getOrPut(key: K, compute: () -> V): V = compute()
}

internal actual fun <K : Any, V : Any> weakMemo(): Memo<K, V> = NoMemo()

internal actual fun <K : Any, V : Any> memo(): Memo<K, V> = NoMemo()
