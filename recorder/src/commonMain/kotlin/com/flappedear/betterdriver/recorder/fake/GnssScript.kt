package com.flappedear.betterdriver.recorder.fake

import com.flappedear.betterdriver.recorder.ChannelValue

/**
 * Made-up fixes a [FakeGnssSource] sends: fix n of [rateHz] is always the same, whenever it is asked
 * for, which lets tests check that a session holds exactly the fixes that were sent.
 */
interface GnssScript {
    val rateHz: Int
    val startUtcMillis: Long

    fun utcMillis(n: Long): Long = startUtcMillis + n * MillisPerSecond / rateHz

    fun fix(n: Long): List<ChannelValue>
}

private const val MillisPerSecond = 1_000L
