@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.io.Closeable
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A simulated ELM327 (FBBD-121): reads commands ending in a carriage return, however they are cut
 * into pieces, and answers each from [answers] followed by the `>` prompt; an unknown command gets
 * `?`, as a real ELM327 answers. [drop] ends the connection from the adapter's side.
 */
class SimElm(val answers: Map<String, String>) {
    val commands: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val pending = StringBuilder()

    /** Bytes the phone wrote; returns the answers they complete, in order. */
    @Synchronized
    fun receive(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c != '\r') {
                pending.append(c)
            } else {
                val cmd = pending.toString().trim()
                pending.setLength(0)
                if (cmd.isNotEmpty()) {
                    commands += cmd
                    out += ((answers[cmd] ?: "?") + "\r\r>").toByteArray(Charsets.US_ASCII)
                }
            }
        }
        return out
    }

    companion object {
        /** A car with RPM (0C), speed (0D), coolant (05) and throttle (11), behind [identity]. */
        fun car(identity: Map<String, String>): Map<String, String> = identity + mapOf(
            "ATE0" to "ATE0\rOK",
            "ATL0" to "OK",
            "ATS0" to "OK",
            "ATH0" to "OK",
            "ATAT1" to "OK",
            "ATSP0" to "OK",
            "ATRV" to "12.4V",
            "ATDPN" to "A6",
            "0100" to "SEARCHING...\r410008188000",
            "010C" to "410C1AF8",
            "010D" to "410D32",
            "0105" to "41055A",
            "0111" to "41117F",
        )

        /** An OBDLink with an STN chip: answers STI and STDI. */
        val Stn = mapOf(
            "ATZ" to "\r\rELM327 v1.4b",
            "ATI" to "ELM327 v1.4b",
            "AT@1" to "OBDLink MX+",
            "STI" to "STN2255 v5.6.19",
            "STDI" to "OBDLink MX+ r1.1",
        )

        /** A vLinker-like adapter: claims ELM327 v2.3, knows no ST commands. */
        val Vlinker = mapOf(
            "ATZ" to "\r\rELM327 v2.3",
            "ATI" to "ELM327 v2.3",
            "AT@1" to "OBDII to RS232 Interpreter",
        )
    }
}

/** A target that never opens, like a paired adapter out of range. */
class DeadTarget(override val where: String, override val transport: ObdTransport) : ObdTarget {
    var tries = 0

    override fun open(bytes: ObdBytes): ObdConnection {
        tries++
        throw IOException("connect failed: read failed, socket might closed or timeout")
    }
}

/**
 * [SimElm] behind a byte stream, as over Bluetooth Classic SPP (or Wi-Fi TCP): the app side gets a
 * [StreamObdConnection] on piped streams, the adapter runs on its own thread. [dropAfter] commands
 * in, the adapter closes the stream, as when it loses power.
 */
class StreamSimTarget(
    private val elm: SimElm,
    override val where: String,
    override val transport: ObdTransport = ObdTransport.BluetoothClassic,
    private val dropAfter: Int = Int.MAX_VALUE,
) : ObdTarget {
    var opens = 0

    override fun open(bytes: ObdBytes): ObdConnection {
        opens++
        val toAdapter = PipedInputStream(4_096)
        val phoneOut = PipedOutputStream(toAdapter)
        val toPhone = PipedInputStream(4_096)
        val adapterOut = PipedOutputStream(toPhone)
        val start = elm.commands.size
        Thread({
            val buf = ByteArray(256)
            try {
                var n = toAdapter.read(buf)
                while (n >= 0 && elm.commands.size - start < dropAfter) {
                    for (answer in elm.receive(buf.copyOf(n))) {
                        adapterOut.write(answer)
                        adapterOut.flush()
                    }
                    n = toAdapter.read(buf)
                }
            } catch (_: IOException) {
                // The phone closed its end.
            }
            adapterOut.close()
        }, "sim-spp").apply {
            isDaemon = true
            start()
        }
        val both = Closeable {
            phoneOut.close()
            toPhone.close()
        }
        return StreamObdConnection(toPhone, phoneOut, both, bytes, "sim-spp-reader")
    }
}

/**
 * [SimElm] behind a Bluetooth LE serial pair: writes of at most [payload] bytes are acknowledged a
 * moment later on another thread, and answers come back as notifications of at most [payload] bytes,
 * on a "binder" thread, as Android delivers them. Records the size of every write.
 */
class BleSimTarget(
    private val elm: SimElm,
    override val where: String,
    private val payload: Int = BleSerialConnection.DEFAULT_PAYLOAD,
) : ObdTarget {
    override val transport = ObdTransport.BluetoothLe
    val writes: MutableList<Int> = Collections.synchronizedList(ArrayList())
    var disconnects = 0

    override fun open(bytes: ObdBytes): ObdConnection {
        val binder = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sim-binder").apply { isDaemon = true }
        }
        lateinit var connection: BleSerialConnection
        val port = object : BleSerialPort {
            override fun write(chunk: ByteArray): Boolean {
                writes += chunk.size
                if (chunk.size > payload) return false
                binder.schedule({
                    connection.writeDone(true)
                    for (answer in elm.receive(chunk)) {
                        answer.toList().chunked(payload).forEach { connection.notified(it.toByteArray()) }
                    }
                }, 2, TimeUnit.MILLISECONDS)
                return true
            }

            override fun disconnect() {
                disconnects++
                binder.shutdownNow()
            }
        }
        connection = BleSerialConnection(port, bytes, payload)
        return connection
    }
}
