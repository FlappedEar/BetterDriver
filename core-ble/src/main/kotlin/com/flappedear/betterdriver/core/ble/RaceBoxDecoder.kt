package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * RaceBox channels beyond the shared [GnssChannels]. Ids 100 to 199 are reserved for the RaceBox,
 * so they cannot collide with channels other sources define.
 */
object RaceBoxChannels {
    const val SOURCE = "racebox"
    val FIX_TYPE = ChannelDef(100, "gnss_fix_type", "", SOURCE, 0)
    val H_ACC = ChannelDef(101, "gnss_hacc", "m", SOURCE, 2)
    val SPEED_ACC = ChannelDef(102, "gnss_speed_acc", "km/h", SOURCE, 2)
    val ACCEL_X = ChannelDef(110, "accel_x", "g", SOURCE, 3)
    val ACCEL_Y = ChannelDef(111, "accel_y", "g", SOURCE, 3)
    val ACCEL_Z = ChannelDef(112, "accel_z", "g", SOURCE, 3)
    val GYRO_X = ChannelDef(113, "gyro_x", "deg/s", SOURCE, 2)
    val GYRO_Y = ChannelDef(114, "gyro_y", "deg/s", SOURCE, 2)
    val GYRO_Z = ChannelDef(115, "gyro_z", "deg/s", SOURCE, 2)
    val INPUT_VOLTS = ChannelDef(120, "racebox_input", "V", SOURCE, 1)

    val ALL = listOf(FIX_TYPE, H_ACC, SPEED_ACC, ACCEL_X, ACCEL_Y, ACCEL_Z, GYRO_X, GYRO_Y, GYRO_Z, INPUT_VOLTS)
}

/** Event kinds the RaceBox source writes into the session. */
object RaceBoxEvents {
    /** Text is the device address. */
    const val CONNECTED = "racebox_connected"
    const val DISCONNECTED = "racebox_disconnected"
    const val FIX_ACQUIRED = "gnss_fix"
    const val FIX_LOST = "gnss_fix_lost"

    /**
     * At start: the motion-sensor calibration applied to this session's acceleration and rotation
     * channels ([ImuCalibration.encode]), or "none". With it the raw values can be recovered.
     */
    const val IMU_CALIBRATION = "imu_calibration"
}

private const val KmhPerMps = 3.6

/**
 * Turns raw notification bytes into sample sets. Each RaceBox Data Message becomes one set,
 * stamped with the recorder's clock when it arrived. Position, speed, heading and altitude are only
 * included while there is a fix, so the VBO export (one row per set with a position) never gets
 * rows at 0°, 0°. Synchronized: notifications may arrive on more than one binder thread.
 *
 * With a [calibration] (FBBD-89), acceleration and rotation are written levelled and with the gyro
 * offset removed.
 */
class RaceBoxDecoder(
    private val sink: SampleSink,
    private val isMicro: Boolean = true,
    private val calibration: ImuCalibration? = null,
) {
    private val reader = UbxFrameReader()
    private var hadFix: Boolean? = null
    private val stats = StreamStats()

    /** Data messages decoded since the link was created. */
    @Volatile
    var messages = 0L
        private set

    val checksumErrors: Long get() = synchronized(this) { reader.checksumErrors }

    fun defineChannels() {
        GnssChannels.ALL.forEach(sink::defineChannel)
        RaceBoxChannels.ALL.filter { isMicro || it != RaceBoxChannels.INPUT_VOLTS }.forEach(sink::defineChannel)
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        reader.feed(bytes) { frame ->
            if (frame.messageClass != RaceBoxClass || frame.messageId != RaceBoxDataId) return@feed
            val data = RaceBoxData.parse(frame.payload) ?: return@feed
            messages++
            stats.add(data.iTowMs)?.let {
                val reads = "${reader.checksumErrors} checksum errors, ${reader.skippedBytes} bytes skipped"
                DebugLog.i("RaceBox", "$it, $reads")
            }
            if (data.hasFix != hadFix) {
                if (hadFix != null || data.hasFix) {
                    val kind = if (data.hasFix) RaceBoxEvents.FIX_ACQUIRED else RaceBoxEvents.FIX_LOST
                    sink.event(kind, "sats=${data.satellites}")
                }
                hadFix = data.hasFix
            }
            sink.submit(SampleSet(sink.nowMicros(), values(data)))
        }
    }

    /** Drops a partial packet left from before a reconnect. */
    @Synchronized
    fun reset() {
        reader.reset()
    }

    private fun values(d: RaceBoxData): List<ChannelValue> = buildList {
        d.utcMillis?.let { add(ChannelValue(GnssChannels.UTC_MS.id, it.toDouble())) }
        add(ChannelValue(GnssChannels.SATS.id, d.satellites.toDouble()))
        add(ChannelValue(RaceBoxChannels.FIX_TYPE.id, d.fixType.toDouble()))
        if (d.hasFix) {
            add(ChannelValue(GnssChannels.LAT.id, d.latDeg))
            add(ChannelValue(GnssChannels.LON.id, d.lonDeg))
            add(ChannelValue(GnssChannels.SPEED_KMH.id, d.speedKmh))
            // Heading of motion, always with a fix. The "valid heading" flag (bit 5) is u-blox's
            // headVehValid, set only in sensor-fusion mode, so it stayed 0 on a real RaceBox and every
            // VBO had heading 000.00 (session 20261011-142759, 637 rows above 5 km/h).
            add(ChannelValue(GnssChannels.HEADING.id, d.headingDeg))
            add(ChannelValue(GnssChannels.ALT_M.id, d.mslAltM))
            add(ChannelValue(RaceBoxChannels.H_ACC.id, d.horizontalAccM))
            add(ChannelValue(RaceBoxChannels.SPEED_ACC.id, d.speedAccMps * KmhPerMps))
        }
        val accel = Vec3(d.accelXg, d.accelYg, d.accelZg).let { calibration?.accel(it) ?: it }
        val gyro = Vec3(d.gyroXdps, d.gyroYdps, d.gyroZdps).let { calibration?.gyro(it) ?: it }
        add(ChannelValue(RaceBoxChannels.ACCEL_X.id, accel.x))
        add(ChannelValue(RaceBoxChannels.ACCEL_Y.id, accel.y))
        add(ChannelValue(RaceBoxChannels.ACCEL_Z.id, accel.z))
        add(ChannelValue(RaceBoxChannels.GYRO_X.id, gyro.x))
        add(ChannelValue(RaceBoxChannels.GYRO_Y.id, gyro.y))
        add(ChannelValue(RaceBoxChannels.GYRO_Z.id, gyro.z))
        if (isMicro) add(ChannelValue(RaceBoxChannels.INPUT_VOLTS.id, d.microInputVolts))
    }
}

/** Expected time between RaceBox data messages: 25 Hz. */
private const val StepMs = 40L
private const val StatsEveryMessages = 1_500

/** A step longer than this has at least one message missing. */
private const val GapMs = 60L

/**
 * Message counts for the debug log (FBBD-147): every [StatsEveryMessages] messages (a minute at
 * 25 Hz) it returns a line with the messages missing from the receiver's time of week, and how many
 * of those fell on a whole second. Arek's 2026-10-12 drive lost the whole-second message in most
 * seconds; these lines show whether the receiver skipped it or the link lost it.
 */
internal class StreamStats {
    private var last: Long? = null
    private var count = 0
    private var missed = 0L
    private var missedOnSecond = 0L

    fun add(iTowMs: Long): String? {
        last?.let { countGap(it, iTowMs) }
        last = iTowMs
        if (++count < StatsEveryMessages) return null
        val line = "$count messages, $missed missing ($missedOnSecond on a whole second)"
        count = 0
        missed = 0
        missedOnSecond = 0
        return line
    }

    /** A step back is the week wrapping or a reconnect: nothing to count. */
    private fun countGap(previous: Long, iTowMs: Long) {
        if (iTowMs - previous <= GapMs) return
        var t = previous + StepMs
        while (t < iTowMs - StepMs / 2) {
            missed++
            if (onWholeSecond(t)) missedOnSecond++
            t += StepMs
        }
    }

    private fun onWholeSecond(t: Long): Boolean {
        val ms = t % MillisPerSecond
        return ms < StepMs / 2 || ms > MillisPerSecond - StepMs / 2
    }

    private companion object {
        const val MillisPerSecond = 1_000L
    }
}
