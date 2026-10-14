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
 * stamped on the recorder's clock from the receiver's time of week ([ReceiverClock]). Position,
 * speed, heading and altitude are only included while there is a fix, so the VBO export (one row
 * per set with a position) never gets rows at 0°, 0°. Synchronized: notifications may arrive on
 * more than one binder thread.
 *
 * With a [calibration] (FBBD-89), acceleration and rotation are written levelled and with the gyro
 * offset removed.
 *
 * [onSlowLink] is told, with the reason, when the link carries fewer messages than the RaceBox sends
 * (FBBD-218): the messages arrive ever later (a backlog over [SlowLinkBacklogMicros]) or one is
 * missing. On Arek's 2026-10-13 drive the delay grew by about 275 ms over two minutes and then the
 * whole-second message was lost every second: 24.3 Hz and 2.9 % dropped, with every message that
 * did arrive in a notification of its own.
 */
class RaceBoxDecoder(
    private val sink: SampleSink,
    private val isMicro: Boolean = true,
    private val calibration: ImuCalibration? = null,
    private val onSlowLink: (String) -> Unit = {},
) {
    private val reader = UbxFrameReader()
    private var hadFix: Boolean? = null
    private val stats = StreamStats()
    private val clock = ReceiverClock()
    private var missedSeen = 0L

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
        stats.notification()
        val decoded = mutableListOf<RaceBoxData>()
        var fixAcquired = false
        reader.feed(bytes) { frame ->
            val isData = frame.messageClass == RaceBoxClass && frame.messageId == RaceBoxDataId
            val data = if (isData) RaceBoxData.parse(frame.payload) else null
            if (data == null) {
                stats.other(frame)
                return@feed
            }
            messages++
            stats.add(data.iTowMs)
            if (data.hasFix != hadFix) {
                if (hadFix != null || data.hasFix) {
                    val kind = if (data.hasFix) RaceBoxEvents.FIX_ACQUIRED else RaceBoxEvents.FIX_LOST
                    sink.event(kind, "sats=${data.satellites}")
                }
                fixAcquired = fixAcquired || hadFix == false && data.hasFix
                hadFix = data.hasFix
            }
            decoded += data
        }
        // One notification can carry several messages; they are stamped together (ReceiverClock).
        if (decoded.isNotEmpty()) {
            // Without a fix the receiver's time is its own guess and may run off GNSS time: on Arek's
            // 2026-10-14 drive the delay it gave rose by 0.5 s in the minute before the fix and then
            // stayed, and the capped rise held every later sample 0.65 s early against the video.
            // So the delay is taken afresh once the fix comes.
            if (fixAcquired) clock.reset()
            val now = sink.nowMicros()
            val stamps = clock.stamp(now, decoded.map { it.iTowMs })
            decoded.zip(stamps) { data, at -> sink.submit(SampleSet(at, values(data))) }
            val backlog = clock.backlogMicros(now, decoded.last().iTowMs)
            stats.backlog(backlog)
            val missedBefore = missedSeen
            missedSeen = stats.missedSoFar
            when {
                backlog > SlowLinkBacklogMicros -> onSlowLink("messages ${backlog / MicrosPerMilli} ms behind")
                missedSeen > missedBefore -> onSlowLink("messages missing")
            }
        }
        // After the whole notification, so its messages and the notification count stay in one line.
        stats.line()?.let {
            val reads = "${reader.checksumErrors} checksum errors, ${reader.skippedBytes} bytes skipped"
            DebugLog.i("RaceBox", "$it, $reads")
        }
    }

    /** Drops a partial packet left from before a reconnect. */
    @Synchronized
    fun reset() {
        reader.reset()
        clock.reset()
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

private const val StatsEveryMessages = 1_500

/** Messages this much later than the link's usual delay mean they queue in the RaceBox (FBBD-218). */
internal const val SlowLinkBacklogMicros = 100_000L
private const val MicrosPerMilli = 1_000L

/** Recent steps whose median is the stream's usual step, as in [RaceBoxDiagnostics]. */
private const val StepWindow = 25
private const val MinSteps = 5

/** A step over this many usual steps has at least one message missing. */
private const val GapSteps = 1.5

/**
 * A step forward longer than this is the receiver's clock jumping, not messages lost: without a fix
 * the RaceBox counts its time of week from a guess, and it jumps to GPS time once it has one. On the
 * 2026-10-14 drive that one jump counted as 6897400 missing messages.
 */
internal const val ClockJumpMs = 5_000L

/**
 * Message counts for the debug log (FBBD-147): after every [StatsEveryMessages] messages (a minute
 * at 25 Hz) it gives a line with the messages missing from the receiver's time of week, how many
 * of those fell on a whole second, the BLE notifications they came in, and every other frame read
 * in that time (class, id and payload length). Arek's 2026-10-12 drives lost the whole-second
 * message in almost every second, but only while the receiver had a fix, with no checksum errors
 * or skipped bytes (FBBD-170). The notification count and the other frames show whether those
 * seconds brought nothing at all or a frame the decoder did not take as data.
 */
internal class StreamStats {
    private var last: Long? = null
    private val steps = ArrayDeque<Long>()
    private var count = 0
    private var missed = 0L
    private var missedOnSecond = 0L
    private var notifications = 0L
    private val others = sortedMapOf<String, Int>()
    private var maxBacklog = 0L

    /** Messages missing since the stream started, for the slow-link check. */
    var missedSoFar = 0L
        private set

    /** The backlog of the newest message of a notification, in microseconds ([ReceiverClock.backlogMicros]). */
    fun backlog(micros: Long) {
        maxBacklog = maxOf(maxBacklog, micros)
    }

    /** One BLE notification arrived. */
    fun notification() {
        notifications++
    }

    /** A whole frame that is not a RaceBox Data Message, or one too short to read. */
    fun other(frame: UbxFrame) {
        val key = "%02x %02x %d B".format(frame.messageClass, frame.messageId, frame.payload.size)
        others[key] = (others[key] ?: 0) + 1
    }

    fun add(iTowMs: Long) {
        last?.let { countGap(it, iTowMs) }
        last = iTowMs
        count++
    }

    /**
     * The line for the messages since the last one, once there are at least [StatsEveryMessages];
     * called after each notification, so one that carries several messages is never split.
     */
    fun line(): String? {
        if (count < StatsEveryMessages) return null
        val otherCount = others.values.sum()
        val otherText = if (otherCount == 0) {
            "0 other frames"
        } else {
            "$otherCount other frames (" + others.entries.joinToString { "${it.key} ×${it.value}" } + ")"
        }
        val line = "$count messages in $notifications notifications, $missed missing " +
            "($missedOnSecond on a whole second), up to ${maxBacklog / MicrosPerMilli} ms behind, $otherText"
        count = 0
        maxBacklog = 0
        missed = 0
        missedOnSecond = 0
        notifications = 0
        others.clear()
        return line
    }

    /**
     * A step back is the week wrapping or a reconnect, a step over 5 s the clock jumping when the fix
     * comes: nothing to count. A step over 1.5 usual steps
     * has messages missing; the usual step is the median of recent ones, so a RaceBox set below
     * 25 Hz for standalone recording is not counted as missing every other message.
     */
    private fun countGap(previous: Long, iTowMs: Long) {
        val gap = iTowMs - previous
        if (gap <= 0 || gap > ClockJumpMs) return
        val step = usualStep()
        steps.addLast(gap)
        if (steps.size > StepWindow) steps.removeFirst()
        if (step == null || gap <= step * GapSteps) return
        var t = previous + step
        while (t < iTowMs - step / 2) {
            missed++
            missedSoFar++
            if (onWholeSecond(t, step)) missedOnSecond++
            t += step
        }
    }

    /** Null for the first few steps of a stream, which are not checked. */
    private fun usualStep(): Long? = if (steps.size < MinSteps) null else steps.sorted()[steps.size / 2]

    private fun onWholeSecond(t: Long, step: Long): Boolean {
        val ms = t % MillisPerSecond
        return ms < step / 2 || ms > MillisPerSecond - step / 2
    }

    private companion object {
        const val MillisPerSecond = 1_000L
    }
}
