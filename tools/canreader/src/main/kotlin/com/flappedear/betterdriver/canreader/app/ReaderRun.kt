package com.flappedear.betterdriver.canreader.app

import android.content.Context
import android.os.SystemClock
import com.flappedear.betterdriver.canreader.FrameTap
import com.flappedear.betterdriver.canreader.FrsReader
import com.flappedear.betterdriver.canreader.ReaderLog
import com.flappedear.betterdriver.core.ble.obd.BleObdTarget
import com.flappedear.betterdriver.obd.ElmLine
import com.flappedear.betterdriver.obd.ObdBytes
import com.flappedear.betterdriver.obd.ObdConnection
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One run of the reader (FBBD-319) on its own thread, kept by the process so turning the phone
 * does not stop it: connect to the adapter over Bluetooth LE ([BleObdTarget], FBBD-121), run the
 * checks or the drive log, write `frs-reader-<time>-<what>.txt` in the app's files.
 */
object ReaderRun {
    enum class What(val label: String) { Checks("checks"), Drive("drive") }

    @Volatile
    var log: ReaderLog? = null
        private set

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    val busy: Boolean get() = thread?.isAlive == true

    fun logDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }

    fun start(context: Context, name: String, address: String, what: What) {
        if (busy) return
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(logDir(app), "frs-reader-$stamp-${what.label}.txt")
        running = true
        thread = Thread({ run(app, name, address, what, file) }, "frs-reader").apply { start() }
    }

    fun stop() {
        running = false
    }

    @Suppress("TooGenericExceptionCaught") // Whatever ends a run is written to its log.
    private fun run(context: Context, name: String, address: String, what: What, file: File) {
        val clock = { SystemClock.elapsedRealtime() }
        file.bufferedWriter().use { out ->
            val log = ReaderLog(out, clock)
            this.log = log
            log.info("FR-S reader ${what.label}, adapter $name $address, file ${file.name}")
            // The Bluetooth LE link's own lines (BleObdTarget) go in the same log.
            DebugLog.echo = { _, tag, message, _ -> log.data("ble", "$tag $message") }
            try {
                connectAndRead(context, name, address, what, log, clock)
            } catch (e: IOException) {
                log.info("stopped: ${e.message}")
            } catch (e: RuntimeException) {
                log.info("failed: $e")
            } finally {
                running = false
                DebugLog.echo = null
                log.info("log saved: ${file.name}")
                log.flush()
            }
        }
    }

    @Suppress("LongParameterList") // The run's settings, passed down from start().
    private fun connectAndRead(
        context: Context,
        name: String,
        address: String,
        what: What,
        log: ReaderLog,
        clock: () -> Long,
    ) {
        val tap = FrameTap(clock)
        lateinit var connection: ObdConnection
        val line = ElmLine(write = { connection.write(it) })
        connection = BleObdTarget(context, name, address).open(object : ObdBytes {
            override fun received(bytes: ByteArray) {
                line.feed(bytes)
                tap.feed(bytes)
            }

            override fun closed(reason: String) {
                log.info("connection closed: $reason")
                line.close(reason)
            }
        })
        connection.use {
            log.info("connected")
            val reader = FrsReader(line, tap, log, clock, { running })
            if (what == What.Checks) reader.checks() else reader.drive()
        }
    }
}
