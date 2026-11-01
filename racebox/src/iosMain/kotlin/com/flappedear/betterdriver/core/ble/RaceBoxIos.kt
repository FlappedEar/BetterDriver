@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.core.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
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

/** Feeds one notification value to the diagnostics probe (FBBD-371); Swift calls `feed(data:nowMs:)`. */
fun RaceBoxDiagnostics.feed(data: NSData, nowMs: Long) {
    feed(data.toByteArray(), nowMs)
}

/** The read-only queries for [info] (FBBD-371) as CoreBluetooth writes them. */
fun RaceBoxDiagnosticsText.queryData(info: RaceBoxDeviceInfo): List<NSData> = queries(info).map { it.toNSData() }

internal fun ByteArray.toNSData(): NSData = usePinned {
    NSData.dataWithBytes(if (isEmpty()) null else it.addressOf(0), size.toULong())
}
