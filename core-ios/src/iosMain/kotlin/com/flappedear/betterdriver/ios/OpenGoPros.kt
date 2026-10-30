@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.gopro.CameraDateTime
import com.flappedear.betterdriver.gopro.GoProClient
import com.flappedear.betterdriver.gopro.GoProException
import com.flappedear.betterdriver.gopro.GoProLink
import com.flappedear.betterdriver.gopro.GoProRecording
import com.flappedear.betterdriver.gopro.GoProRecordingsJson
import com.flappedear.betterdriver.gopro.GoProVideo
import com.flappedear.betterdriver.gopro.OpenGoPro
import com.flappedear.betterdriver.gopro.OpenGoProCamera
import com.flappedear.betterdriver.gopro.PhoneClock
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.logInfo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import com.flappedear.betterdriver.video.IoProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTimeZone
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

/**
 * The Open GoPro BLE link (GoProLink) of one camera on the iPhone (FBBD-343): the app's
 * CoreBluetooth code subclasses it and does each step it is asked to ([beginConnect],
 * [beginWrite], [beginRead], [beginDisconnect]), then calls back with the outcome ([connected],
 * [written], [readDone], [disconnected]) and each notification ([notified]). Calls come and go on
 * the main thread, as the shared start and stop ([OpenGoProCamera]) runs there.
 */
@Suppress("TooManyFunctions") // One call per BLE step and one callback per outcome.
abstract class IosGoProLink : GoProLink {
    private val packets = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    override val notifications: Flow<Pair<String, ByteArray>> = packets

    private var connecting: CompletableDeferred<Unit>? = null
    private var writing: CompletableDeferred<Unit>? = null
    private var reading: CompletableDeferred<ByteArray>? = null

    /** Connect, discover the Open GoPro services and subscribe to the three response characteristics. */
    abstract fun beginConnect()

    /** Write [packet] to [characteristic], with response. */
    abstract fun beginWrite(characteristic: String, packet: NSData)

    abstract fun beginRead(characteristic: String)

    /** Disconnect, or stop connecting. Needs no answer. */
    abstract fun beginDisconnect()

    override suspend fun connect() {
        val done = CompletableDeferred<Unit>()
        connecting = done
        beginConnect()
        try {
            done.await()
        } catch (e: CancellationException) {
            beginDisconnect()
            throw e
        }
    }

    override suspend fun write(characteristic: String, packet: ByteArray) {
        val done = CompletableDeferred<Unit>()
        writing = done
        beginWrite(characteristic, packet.toNSData())
        done.await()
    }

    override suspend fun read(characteristic: String): ByteArray {
        val done = CompletableDeferred<ByteArray>()
        reading = done
        beginRead(characteristic)
        return done.await()
    }

    override suspend fun disconnect() {
        beginDisconnect()
        disconnected(null)
    }

    /** Connected and subscribed, or [error] why not. */
    fun connected(error: String?) {
        val done = connecting ?: return
        connecting = null
        if (error == null) done.complete(Unit) else done.completeExceptionally(IoProblem(error, null))
    }

    fun written(error: String?) {
        val done = writing ?: return
        writing = null
        if (error == null) done.complete(Unit) else done.completeExceptionally(IoProblem(error, null))
    }

    fun readDone(value: NSData?, error: String?) {
        val done = reading ?: return
        reading = null
        if (value != null && error == null) {
            done.complete(value.toByteArray())
        } else {
            done.completeExceptionally(IoProblem(error ?: "no value", null))
        }
    }

    fun notified(characteristic: String, packet: NSData) {
        packets.tryEmit(characteristic.lowercase() to packet.toByteArray())
    }

    /** The link is gone: whatever waits on it fails with [error] ("disconnected" when null). */
    fun disconnected(error: String?) {
        val problem = IoProblem(error ?: "disconnected", null)
        connecting?.completeExceptionally(problem)
        writing?.completeExceptionally(problem)
        reading?.completeExceptionally(problem)
        connecting = null
        writing = null
        reading = null
    }
}

/** The iPhone's clock and time zone, for setting a camera's clock. */
object IosPhoneClock : PhoneClock {
    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong()

    override fun local(utcMillis: Long): CameraDateTime {
        val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
            NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
        // Gregorian whatever calendar the iPhone shows (a Buddhist year would set the wrong date).
        val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
        calendar.timeZone = NSTimeZone.localTimeZone
        val c = calendar.components(units, fromDate = date(utcMillis))
        return CameraDateTime(
            c.year.toInt(), c.month.toInt(), c.day.toInt(), c.hour.toInt(), c.minute.toInt(), c.second.toInt(),
        )
    }

    override fun utcOffsetMinutes(utcMillis: Long): Int =
        (NSTimeZone.localTimeZone.secondsFromGMTForDate(date(utcMillis)) / SecondsPerMinute).toInt()

    override fun daylightSaving(utcMillis: Long): Boolean =
        NSTimeZone.localTimeZone.isDaylightSavingTimeForDate(date(utcMillis))

    private fun date(utcMillis: Long) = NSDate.dateWithTimeIntervalSince1970(utcMillis / MillisPerSecond)

    private const val MillisPerSecond = 1000.0
    private const val SecondsPerMinute = 60
}

/** An Open GoPro camera turned on for the next session, as the START sheet and the recording need it. */
data class SessionGoPro(
    /** The equipment item's id. */
    val id: String,
    /** Its camera id in the session's video ([CameraIds.goPro]). */
    val sourceId: String,
    val name: String,
    /** The iPhone's Bluetooth identifier for the camera. */
    val address: String,
    val on: Boolean,
    val video: GoProVideo?,
)

/** Which Open GoPro camera pairing found: its model, from Get Hardware Info. */
data class PairedOpenGoPro(val model: GoProModel, val modelName: String)

/**
 * The Open GoPro cameras of a session on the iPhone (FBBD-343), as Android's GoPros: each camera
 * turned on for the session starts recording when it starts and stops when it ends, and every
 * failure stays with that camera, never the recording. Acknowledgements of start and stop go to
 * the session's events while it records and always to the app log (FBBD-82), the recording note
 * (with the stop's time once the camera confirmed it) to `<session>/video/gopro-recordings.json`
 * for the download after the drive.
 */
class OpenGoPros {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running: List<OpenGoProCamera> = emptyList()

    /** Starts [goPro] over [link] with [session]. */
    fun start(session: LiveSession, goPro: SessionGoPro, link: IosGoProLink) {
        val sessionId = session.id
        val videoDir = "${session.dir}/${VideoFiles.DIR}"
        val camera = OpenGoProCamera(goPro.sourceId, link, scope, IosPhoneClock, goPro.video, acks = { ack ->
            val result = if (ack.ok) "ok" else "failed"
            val text = "camera=${goPro.sourceId} action=${ack.action} result=$result"
            logInfo(GoProTag, "$sessionId: $text")
            // START and STOP alike (audit 30 Oct BD-05). A STOP that answers after the session ended
            // stays in the app log, and its time in the recording note (stopAck*, none = not confirmed).
            if (session.isRecording) session.eventAt(ack.sessionUs, EventKinds.GOPRO, text)
        })
        running = running + camera
        scope.launch { camera.state.collect { logInfo(GoProTag, "${goPro.sourceId}: $it") } }
        camera.start(sessionClock = { session.nowMicros() }) { r -> save(videoDir, r) }
    }

    /** Stops every camera; each answers, or gives up, in its own time, and then nothing is left running. */
    fun stop() {
        val stopping = running
        running = emptyList()
        scope.launch {
            stopping.map { camera ->
                launch {
                    camera.stop()
                    camera.close()
                }
            }.joinAll()
            scope.cancel()
        }
    }

    private fun save(dir: String, recording: GoProRecording) {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val path = "$dir/${GoProRecordingsJson.FileName}"
        val all = GoProRecordingsJson.put(readText(path)?.let(GoProRecordingsJson::decode).orEmpty(), recording)
        writeBytesAtomically(path, GoProRecordingsJson.encode(all).encodeToByteArray())
    }

    companion object {
        /** The Open GoPro cameras in [setup], each with whether it is on for the next session. */
        fun listed(setup: Setup): List<SessionGoPro> {
            val on = setup.active().cameras.map { it.id }.toSet()
            return setup.equipment.filter { it.model == EquipmentModel.GOPRO && it.address != null }.map {
                SessionGoPro(
                    it.id, CameraIds.goPro(it.id), it.name, it.address.orEmpty(), it.id in on,
                    it.videoMode?.let(::goProVideo),
                )
            }
        }

        /**
         * Pairs with the camera over [link] (in pairing mode the first time: the iPhone asks to
         * pair) and says which camera it is, or why not, as Android's pairing. The model number
         * decides; a number this build does not know is still an Open GoPro camera.
         */
        fun pair(link: IosGoProLink, done: (PairedOpenGoPro?, String?) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val client = GoProClient(link, scope)
            scope.launch {
                val result = try {
                    withTimeout(PairTimeoutMillis) {
                        link.connect()
                        val info = OpenGoPro.HardwareInfo.parse(client.send(OpenGoPro.hardwareInfo()).payload)
                        val byNumber = GoProModel.ofModelId(info?.modelNumber)
                        val model = byNumber.takeIf { it != GoProModel.OTHER_OPEN_GOPRO }
                            ?: GoProModel.ofName(info?.modelName)
                            ?: byNumber
                        Result.success(PairedOpenGoPro(model, info?.modelName?.takeIf { it.isNotBlank() } ?: "GoPro"))
                    }
                } catch (_: TimeoutCancellationException) {
                    Result.failure(GoProException("The GoPro did not answer"))
                } catch (e: IoProblem) {
                    Result.failure(e)
                }
                client.close()
                runCatching { link.disconnect() }
                result.fold({ done(it, null) }, { done(null, "Pairing failed: ${it.message}") })
            }
        }

        private const val PairTimeoutMillis = 45_000L
    }
}

/**
 * Open GoPro setting values for a video mode (FBBD-88), as Android's goProVideo; null for a size or
 * rate Open GoPro has no value for here.
 */
internal fun goProVideo(mode: VideoMode): GoProVideo? {
    val resolution = when (mode.size) {
        VideoSize.UHD -> OpenGoPro.Setting.RES_4K
        VideoSize.QHD -> OpenGoPro.Setting.RES_2_7K
        VideoSize.FHD -> OpenGoPro.Setting.RES_1080
        VideoSize.HD -> OpenGoPro.Setting.RES_720
    }
    val fps = when (mode.fps) {
        HighFps -> OpenGoPro.Setting.FPS_60
        LowFps -> OpenGoPro.Setting.FPS_30
        else -> null
    }
    return if (fps != null) GoProVideo(resolution, fps, mode.label, mode.size.label) else null
}

private const val HighFps = 60
private const val LowFps = 30

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
