package com.flappedear.betterdriver.analysis.data

import java.util.Collections
import java.util.WeakHashMap

private class MapMemo<K : Any, V : Any>(private val map: MutableMap<K, V>) : Memo<K, V> {
    override fun getOrPut(key: K, compute: () -> V): V = map.getOrPut(key, compute)
}

internal actual fun <K : Any, V : Any> weakMemo(): Memo<K, V> = MapMemo(Collections.synchronizedMap(WeakHashMap()))

internal actual fun <K : Any, V : Any> memo(): Memo<K, V> = MapMemo(Collections.synchronizedMap(HashMap()))
