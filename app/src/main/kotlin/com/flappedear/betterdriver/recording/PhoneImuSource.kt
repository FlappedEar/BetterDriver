package com.flappedear.betterdriver.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.PhoneGnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.log.DebugLog

/** The phone's own accelerometer, in g along the phone's axes, gravity included like the RaceBox's. */
object PhoneImuChannels {
    const val SOURCE = PhoneGnssChannels.SOURCE
    val ACCEL_X = ChannelDef(700, "phone_accel_x", "g", SOURCE, 3)
    val ACCEL_Y = ChannelDef(701, "phone_accel_y", "g", SOURCE, 3)
    val ACCEL_Z = ChannelDef(702, "phone_accel_z", "g", SOURCE, 3)
    val ALL = listOf(ACCEL_X, ACCEL_Y, ACCEL_Z)
}

/** 25 Hz, the RaceBox's rate: plenty for a car's G and a small share of the session file. */
private const val SamplingPeriodMicros = 40_000

private const val StandardGravity = 9.80665
private const val NanosPerMicro = 1_000L

/** An event older than this is stamped when it arrives: its timestamp is on an unexpected clock. */
private const val MaxAgeMicros = 1_000_000L

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
                val now = sink.nowMicros()
                val age = (SystemClock.elapsedRealtimeNanos() - e.timestamp) / NanosPerMicro
                val at = if (age in 0..MaxAgeMicros) now - age else now
                sink.submit(
                    SampleSet(
                        at,
                        listOf(
                            ChannelValue(PhoneImuChannels.ACCEL_X.id, e.values[0] / StandardGravity),
                            ChannelValue(PhoneImuChannels.ACCEL_Y.id, e.values[1] / StandardGravity),
                            ChannelValue(PhoneImuChannels.ACCEL_Z.id, e.values[2] / StandardGravity),
                        ),
                    ),
                )
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
