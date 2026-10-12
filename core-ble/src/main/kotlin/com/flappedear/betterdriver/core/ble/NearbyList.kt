package com.flappedear.betterdriver.core.ble

/** A device that has not been heard for this long leaves the list. */
const val ForgetNearbyAfterMs = 5_000L

/**
 * The devices of one kind heard in a scan in the last [forgetAfterMs], strongest first, by [key]
 * (the Bluetooth address). Pure, so the list rules are tested on the JVM. Not thread-safe.
 */
class NearbyList<T>(
    private val key: (T) -> String,
    private val rssi: (T) -> Int,
    private val forgetAfterMs: Long = ForgetNearbyAfterMs,
) {
    private val seen = LinkedHashMap<String, Pair<T, Long>>()

    /** [device] was heard at [nowMs]; it replaces what was heard from the same address. */
    fun heard(device: T, nowMs: Long) {
        seen[key(device)] = device to nowMs
    }

    /** The devices heard in the last [forgetAfterMs] before [nowMs], strongest first. */
    fun current(nowMs: Long): List<T> {
        seen.values.removeAll { nowMs - it.second > forgetAfterMs }
        return seen.values.map { it.first }.sortedByDescending(rssi)
    }
}
