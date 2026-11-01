@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.core.ble.tpms

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Feeds one CoreBluetooth notification value to the session (FBBD-224). Swift calls it as
 * `session.feed(data:)`; copying into a ByteArray here avoids building a KotlinByteArray byte by byte.
 */
fun TpmsSession.feed(data: NSData) {
    feed(data.toByteArray())
}

/** [TpmsIds.AllowAllEverySecond] as the Data CoreBluetooth writes; Swift calls `TpmsIosKt.tpmsFilterData()`. */
fun tpmsFilterData(): NSData {
    val bytes = TpmsIds.AllowAllEverySecond
    return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
}

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
