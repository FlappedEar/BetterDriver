@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.core.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Feeds one CoreBluetooth notification value to the session (FBBD-332). Swift calls it as
 * `session.feed(data:)`; copying into a ByteArray here avoids building a KotlinByteArray byte by byte.
 */
fun RaceBoxSession.feed(data: NSData) {
    feed(data.toByteArray())
}

internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
