@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.posix.memcpy

/**
 * A Bluetooth LE OBD-II adapter as the iOS app reaches it over CoreBluetooth (FBBD-333); the Swift
 * link implements it. [open] connects, finds the serial pair ([BleSerial.choose]) and turns its
 * notifications on, blocking the calling (OBD) thread until that is done or failed; it returns null
 * when the line is ready, else why not. From then on the link passes notifications, write results
 * and a lost link to [events]. [payload] is the longest write the link takes, known once open.
 */
interface ObdBlePort {
    val payload: Int

    fun open(events: ObdBleEvents): String?

    /** Starts writing [chunk]; false when CoreBluetooth refused it. */
    fun write(chunk: NSData): Boolean

    fun disconnect()
}

/** What the Swift link reports about an open [ObdBlePort], on its own queue. */
interface ObdBleEvents {
    fun notified(value: NSData)

    fun writeDone(ok: Boolean)

    fun lost(reason: String)
}

/**
 * A Bluetooth LE ELM327 on iOS (FBBD-333), the iOS side of the Android app's BleObdTarget: [port]
 * opens it, and a [BleSerialConnection] cuts commands to its payload and paces them by the write
 * acknowledgements, as on Android. [identifier] is CoreBluetooth's, which iOS keeps in place of a
 * Bluetooth address.
 */
class IosBleObdTarget(override val name: String, identifier: String, private val port: ObdBlePort) : ObdTarget {
    override val where = "$name (Bluetooth LE)"
    override val transport = ObdTransport.BluetoothLe
    override val address = identifier

    override fun open(bytes: ObdBytes): ObdConnection {
        val relay = Relay()
        port.open(relay)?.let { throw IOException(it) }
        val connection = BleSerialConnection(PortWriter(port), bytes, port.payload.coerceAtLeast(1))
        relay.connection = connection
        return connection
    }

    private class Relay : ObdBleEvents {
        @Volatile
        var connection: BleSerialConnection? = null

        override fun notified(value: NSData) {
            connection?.notified(value.toByteArray())
        }

        override fun writeDone(ok: Boolean) {
            connection?.writeDone(ok)
        }

        override fun lost(reason: String) {
            connection?.lost(reason)
        }
    }

    private class PortWriter(private val port: ObdBlePort) : BleSerialPort {
        override fun write(chunk: ByteArray): Boolean = port.write(chunk.toNSData())

        override fun disconnect() = port.disconnect()
    }
}

/** What the iOS link reports from the OBD thread, for its log. */
interface ObdFeedListener {
    fun log(text: String)
}

private const val NanosPerMs = 1_000_000L
private const val MsPerSecond = 1_000.0

/**
 * OBD-II in an iOS recording (FBBD-333), as the Android app's ObdSource and ObdLink: an
 * [ObdConnector] on a thread of its own tries [targets] in order, sets the adapter up, reads the
 * car's channels in the driver's set ([PollSet.Driver]; on an FR-S its CAN broadcasts, told by its
 * VIN) and records them into [sink] through an [ObdSession]. A lost adapter is retried with the
 * Android delays; nothing it throws reaches the recording. [status] is the connection as the track
 * screen shows it.
 */
class ObdFeed(
    sink: SampleSink,
    private val targets: () -> List<ObdTarget>,
    private val listener: ObdFeedListener,
) {
    private val clock = object : ObdClock {
        override fun nowMs() = monoNanos() / NanosPerMs

        override fun sleep(ms: Long) = NSThread.sleepForTimeInterval(ms / MsPerSecond)
    }
    private val tracker = ObdStatusTracker()
    private val session = ObdSession(sink, clock::nowMs)

    @Volatile
    private var running = false
    private var connector: ObdConnector? = null

    fun status(): ObdStatus = tracker.snapshot(clock.nowMs())

    fun start() {
        if (running) return
        running = true
        val c = ObdConnector(targets, Relay(), tracker, clock, pollSet = { PollSet.Driver })
        connector = c
        val thread = NSThread {
            read(c)
        }
        thread.name = "obd"
        thread.start()
    }

    fun stop() {
        running = false
        connector?.stop()
        connector = null
    }

    /** An adapter may be in range again (Bluetooth on, an adapter found): try at once. */
    fun retryNow() {
        connector?.retryNow()
    }

    @Suppress("TooGenericExceptionCaught") // Whatever goes wrong, car data ends and the recording goes on.
    private fun read(c: ObdConnector) {
        try {
            c.run { running }
            listener.log("reader stopped")
        } catch (e: Throwable) {
            listener.log("reader failed: $e")
        }
    }

    private inner class Relay : ObdConnectorListener {
        override fun ready(
            target: ObdTarget,
            adapter: String,
            protocol: String,
            voltage: String?,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) = session.ready(target, adapter, protocol, supported, channels)

        override fun polling(target: ObdTarget, channels: List<PidChannel>) = session.polling(channels)

        override fun value(channel: PidChannel, value: Double, atMs: Long) = session.value(channel, value, atMs)

        override fun ended(target: ObdTarget, reason: String) = session.ended(reason)

        override fun log(text: String) = listener.log(text)
    }
}

internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
