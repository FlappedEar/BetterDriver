package com.flappedear.betterdriver.ios

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * The Kotlin core's memory, for the app's memory tests (FBBD-393): [collect] runs a full garbage
 * collection, so what earlier work left behind is gone before a test measures what one task adds.
 */
object CoreMemory {
    @OptIn(NativeRuntimeApi::class)
    fun collect() = GC.collect()
}
