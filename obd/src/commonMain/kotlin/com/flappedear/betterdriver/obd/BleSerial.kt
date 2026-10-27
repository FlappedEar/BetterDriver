package com.flappedear.betterdriver.obd

/**
 * A GATT characteristic as service discovery found it; [properties] are Android's PROPERTY_* bits
 * (the same bits as CoreBluetooth's CBCharacteristicProperties). [service] and [uuid] are UUID texts
 * in any form a platform prints them ("FFF0", upper or lower case); they are compared as
 * [BleSerial.uuid] makes them.
 */
class GattCharacteristic(service: String, uuid: String, val properties: Int) {
    val service: String = BleSerial.uuid(service)
    val uuid: String = BleSerial.uuid(uuid)

    override fun equals(other: Any?) =
        other is GattCharacteristic && other.service == service && other.uuid == uuid && other.properties == properties

    override fun hashCode() = (service.hashCode() * HashPrime + uuid.hashCode()) * HashPrime + properties

    override fun toString() = "GattCharacteristic(service=$service, uuid=$uuid, properties=$properties)"

    private companion object {
        const val HashPrime = 31
    }
}

/**
 * The serial pair of a Bluetooth LE ELM327 (FBBD-121): [notify] carries the adapter's answers to the
 * phone, [write] takes the phone's commands; they can be one characteristic. [withResponse] is true
 * when [write] takes acknowledged writes, which pace the phone to the adapter.
 */
data class BleSerialPair(val service: String, val notify: String, val write: String, val withResponse: Boolean)

/**
 * Finds the serial characteristics of a Bluetooth LE OBD-II adapter. These adapters have no standard
 * service: each maker exposes a UART-like pair of its own. The ones in [Known] are tried first; any
 * other adapter works if one of its own services has a characteristic that notifies and one that
 * takes writes.
 */
object BleSerial {
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20

    /** A known pair: service, notify characteristic, write characteristic. */
    private data class Profile(val service: String, val notify: String, val write: String)

    /**
     * Pairs seen on ELM327 adapters that work over Bluetooth LE. FFF0 (FFF1 notify, FFF2 write) is
     * the usual one on generic ELM327 clones; FFE0/FFE1 is the HM-10 style single characteristic;
     * E7810A71/BEF8D6C9 is the one iOS OBD apps use for Vgate adapters; 18F0 (2AF0, 2AF1) is on
     * some other makes. None is from a maker's document, so [choose] also accepts any other pair.
     */
    private val Known = listOf(
        Profile(short(0xFFF0), short(0xFFF1), short(0xFFF2)),
        Profile(short(0xFFE0), short(0xFFE1), short(0xFFE1)),
        Profile(
            "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
            "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
            "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
        ),
        Profile(short(0x18F0), short(0x2AF0), short(0x2AF1)),
    )

    /** Services every device has (GAP, GATT, device information, battery): never the serial line. */
    private val Standard = setOf(0x1800, 0x1801, 0x180A, 0x180F).map(::short).toSet()

    /** The serial pair among [characteristics], or null when the device has none. */
    fun choose(characteristics: List<GattCharacteristic>): BleSerialPair? =
        Known.firstNotNullOfOrNull { known(it, characteristics) } ?: generic(characteristics)

    private fun known(p: Profile, all: List<GattCharacteristic>): BleSerialPair? {
        val inService = all.filter { it.service == p.service }
        val notify = inService.firstOrNull { it.uuid == p.notify && it.notifies() }
        val write = inService.firstOrNull { it.uuid == p.write && it.writes() }
        return if (notify == null || write == null) null else pair(p.service, notify, write)
    }

    private fun pair(service: String, notify: GattCharacteristic, write: GattCharacteristic) =
        BleSerialPair(service, notify.uuid, write.uuid, write.properties and PROPERTY_WRITE != 0)

    /** The first non-standard service with a notifying and a writable characteristic. */
    private fun generic(all: List<GattCharacteristic>): BleSerialPair? = all
        .filter { it.service !in Standard }
        .groupBy { it.service }
        .firstNotNullOfOrNull { (service, chars) ->
            val notify = chars.firstOrNull { it.notifies() }
            // One characteristic that does both is the line; else the first writable one.
            val write = chars.firstOrNull { it.writes() && it.uuid == notify?.uuid }
                ?: chars.firstOrNull { it.writes() }
            if (notify == null || write == null) null else pair(service, notify, write)
        }

    private fun GattCharacteristic.notifies() = properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0

    private fun GattCharacteristic.writes() = properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0

    /** A 16-bit UUID on the Bluetooth base UUID, as text: "0000fff0-0000-1000-8000-00805f9b34fb". */
    fun short(id: Int): String = "0000${id.toString(HEX).padStart(SHORT_DIGITS, '0')}$BASE_SUFFIX"

    /**
     * [text] as a full lower-case UUID, the form java.util.UUID prints: a 16-bit ("FFF0") or 32-bit
     * short form, as CoreBluetooth prints them, goes on the Bluetooth base UUID.
     */
    fun uuid(text: String): String {
        val t = text.trim().lowercase()
        return when (t.length) {
            SHORT_DIGITS -> "0000$t$BASE_SUFFIX"
            LONG_DIGITS -> "$t$BASE_SUFFIX"
            else -> t
        }
    }

    private const val HEX = 16
    private const val SHORT_DIGITS = 4
    private const val LONG_DIGITS = 8
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
}

/** The GATT side a [BleSerialConnection] drives; the app implements it over BluetoothGatt. */
interface BleSerialPort {
    /** Starts writing [chunk] to the write characteristic; false when the stack refused it. */
    fun write(chunk: ByteArray): Boolean

    /** Disconnects and releases the GATT connection. */
    fun disconnect()
}

/**
 * An [ObdConnection] over a Bluetooth LE serial pair (FBBD-121). Commands are cut into pieces of
 * [payload] bytes (the MTU minus the 3-byte ATT header) and written one at a time: Bluetooth LE
 * takes one GATT operation at once, so each piece waits for [writeDone] before the next. The GATT
 * callback passes notifications to [notified] and a lost link to [lost].
 */
class BleSerialConnection(
    private val port: BleSerialPort,
    private val bytes: ObdBytes,
    private val payload: Int,
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
) : ObdConnection {
    private val lock = ObdMonitor()
    private var ended: String? = null
    private var pending = false
    private var failed = false
    private var released = false

    /** [ended] has been passed to [bytes], so a waiting write may fail now. */
    private var reported = false

    init {
        require(payload > 0) { "payload must be positive: $payload" }
    }

    override fun write(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + payload, bytes.size)
            writeChunk(bytes.copyOfRange(at, end))
            at = end
        }
    }

    private fun writeChunk(chunk: ByteArray) {
        lock.locked {
            ended?.let { throw IOException(it) }
            pending = true
            failed = false
        }
        val accepted = port.write(chunk)
        val error = lock.locked { if (accepted) awaitAck() else "write refused".also { pending = false } }
        error?.let { throw IOException(it) }
    }

    /** Under [lock]: waits for [writeDone]; null when the write went through, else why not. */
    private fun awaitAck(): String? {
        val deadline = monoNanos() + writeTimeoutMs * NanosPerMs
        var interrupted = false
        while (pending && !reported && !interrupted) {
            val left = (deadline - monoNanos()) / NanosPerMs
            if (left <= 0) return "write not acknowledged in $writeTimeoutMs ms"
            try {
                lock.await(left)
            } catch (_: IOException) {
                // Stopping a session interrupts the OBD thread; a write then fails, never crashes (FBBD-269).
                interrupted = true
            }
        }
        return ended ?: if (interrupted) "interrupted" else if (failed) "write failed" else null
    }

    /** The GATT stack finished the last write, [ok] when it succeeded. */
    fun writeDone(ok: Boolean) {
        lock.locked {
            pending = false
            failed = !ok
            lock.signalAll()
        }
    }

    /** A notification from the adapter. */
    fun notified(value: ByteArray) {
        if (lock.locked { ended == null }) bytes.received(value)
    }

    /** The link dropped, with [reason]; [close] still releases the GATT connection. */
    fun lost(reason: String) = end(reason)

    override fun close() {
        end("closed")
        val first = lock.locked { !released.also { released = true } }
        if (first) port.disconnect()
    }

    private fun end(reason: String) {
        lock.locked {
            if (ended != null) return
            ended = reason
        }
        // The reader hears why before a waiting write fails, so the line closes with this reason.
        bytes.closed(reason)
        lock.locked {
            reported = true
            lock.signalAll()
        }
    }

    companion object {
        const val DEFAULT_WRITE_TIMEOUT_MS = 2_000L

        /** The payload of the default 23-byte MTU. */
        const val DEFAULT_PAYLOAD = 20
        private const val NanosPerMs = 1_000_000L
    }
}
