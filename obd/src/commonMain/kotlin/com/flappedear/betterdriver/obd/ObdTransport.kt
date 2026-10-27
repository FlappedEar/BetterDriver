package com.flappedear.betterdriver.obd

/** What an open [ObdConnection] hands to its owner, on the transport's own thread. */
interface ObdBytes {
    /** Bytes from the adapter, in the order they arrived. */
    fun received(bytes: ByteArray)

    /** The connection is gone, with why; called at most once. */
    fun closed(reason: String)
}

/** One open serial connection to an ELM327 adapter (FBBD-121). */
interface ObdConnection : AutoCloseable {
    /** Sends [bytes] to the adapter; throws an [IOException] when the connection is gone. */
    fun write(bytes: ByteArray)

    /** Ends the connection; [ObdBytes.closed] follows. Safe to call more than once. */
    override fun close()
}

/** How an adapter is reached, for the log and the session event. */
enum class ObdTransport(val label: String) {
    Wifi("Wi-Fi"),
    BluetoothClassic("Bluetooth"),
    BluetoothLe("Bluetooth LE"),
}

/**
 * One adapter to try (FBBD-15, FBBD-121): a Wi-Fi ELM327, a paired Bluetooth Classic one (SPP) or a
 * Bluetooth LE one with a serial characteristic. [open] connects, blocking until the line is ready,
 * and throws an [IOException] when it cannot; bytes then go to the [ObdBytes] on the transport's
 * thread. [where] names the adapter in the log, [name] on the OBD page (UI-106 A: the name Android
 * shows for a Bluetooth adapter), [transport] says how it is reached. [address] is a Bluetooth
 * adapter's, logged with its connection so the drive checks can tell which listed adapter it was
 * (FBBD-280); null for Wi-Fi.
 */
interface ObdTarget {
    val where: String
    val transport: ObdTransport
    val name: String get() = where
    val address: String? get() = null

    fun open(bytes: ObdBytes): ObdConnection
}
