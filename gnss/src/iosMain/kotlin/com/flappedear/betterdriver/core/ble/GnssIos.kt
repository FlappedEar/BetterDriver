@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.core.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Feeds what an iPhone link received to the session (FBBD-374). Swift calls it as
 * `session.feed(data:)`; copying into a ByteArray here avoids building a KotlinByteArray byte by byte.
 */
fun GnssReceiverSession.feed(data: NSData) {
    feed(data.toByteArray())
}

/** A RaceChrono DIY GPS time value; Swift calls `session.feedTime(data:)`. */
fun GnssReceiverSession.feedTime(data: NSData) {
    feedTime(data.toByteArray())
}

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
