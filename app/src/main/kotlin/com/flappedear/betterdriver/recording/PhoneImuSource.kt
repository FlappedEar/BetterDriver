package com.flappedear.betterdriver.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.PhoneImuChannels
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.phoneAccelSet
import com.flappedear.betterdriver.recorder.phoneImuMicrosByAge
import com.flappedear.betterdriver.recorder.log.DebugLog

/** 25 Hz, the RaceBox's rate: plenty for a car's G and a small share of the session file. */
private const val SamplingPeriodMicros = 40_000

private const val StandardGravity = 9.80665
private const val NanosPerMicro = 1_000L

/**
 * The phone's accelerometer as a recorder [DataSource] (FBBD-218). Recorded in every session next
 * to the receiver, so the car's G comes from a real accelerometer even when the receiver has none or
 * its readings do not match the GPS: analysis fits whichever accelerometer agrees with GPS, the
 * receiver's first, in whatever way the phone sits in its mount.
 *
 * A reading is stamped when it was measured: its age (boot clock now minus the event's timestamp,
 * which Android takes on the boot clock) before the recorder's time at delivery.
 */
class PhoneImuSource(private val context: Context) : DataSource {
    override val name = "phone_imu"

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override fun start(sink: SampleSink) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            DebugLog.i(Tag, "no accelerometer")
            return
        }
        PhoneImuChannels.ALL.forEach(sink::defineChannel)
        val worker = HandlerThread("phone-imu").apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val age = (SystemClock.elapsedRealtimeNanos() - e.timestamp) / NanosPerMicro
                val at = phoneImuMicrosByAge(sink.nowMicros(), age)
                val (x, y, z) = e.values.map { it / StandardGravity }
                sink.submit(phoneAccelSet(at, x, y, z))
            }

            override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit
        }
        val ok = manager.registerListener(l, sensor, SamplingPeriodMicros, Handler(worker.looper))
        DebugLog.i(Tag, "accelerometer ${sensor.name}: ${if (ok) "recording" else "refused"}")
        thread = worker
        listener = l
    }

    override fun stop() {
        listener?.let { context.getSystemService(SensorManager::class.java)?.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val Tag = "PhoneImu"
    }
}
