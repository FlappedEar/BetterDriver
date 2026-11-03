package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.core.ble.DiagHeaderText
import com.flappedear.betterdriver.core.ble.DiagRow
import com.flappedear.betterdriver.core.ble.DiagSection
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.video.IoProblem
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What a GoPro's live data page shows, at one moment (FBBD-440). */
data class GoProLiveSnapshot(
    /** Connected and answering. */
    val connected: Boolean,
    /** Why the last attempt failed, until the next one connects. */
    val error: String?,
    val info: OpenGoPro.HardwareInfo?,
    /** The newest value of each polled status ([GoProLive.Statuses]). */
    val statuses: Map<Int, Long>,
    val polls: Long,
    /** Milliseconds since the newest answer, or null before the first. */
    val sinceLastMs: Long?,
)

/**
 * An Open GoPro camera's live status for its Equipment page (FBBD-440): connects over [link], reads
 * Get Hardware Info once, then asks the camera's status once a second and keeps it awake. It only
 * reads: it never starts recording, changes a setting or turns Wi-Fi on. A lost camera is tried again
 * every few seconds until [stop]. The Android app and the iPhone run it on their own Bluetooth link,
 * on the main thread. [scope] must outlive [stop], which lets the camera go in it.
 */
class GoProLive(
    private val link: GoProLink,
    private val scope: CoroutineScope,
    private val connectTimeoutMillis: Long = 20_000,
    private val pollMillis: Long = 1_000,
    private val retryMillis: Long = 3_000,
) {
    private val clock = TimeSource.Monotonic.markNow()
    private var job: Job? = null
    private var client: GoProClient? = null

    // Written by the polling coroutine, read by the page; each field is replaced whole.
    private var connected = false
    private var error: String? = null
    private var info: OpenGoPro.HardwareInfo? = null
    private var statuses: Map<Int, Long> = emptyMap()
    private var polls = 0L
    private var lastMs: Long? = null
    private var asked: IntArray = Statuses

    fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    /** Stops polling and lets the camera go. */
    fun stop() {
        job?.cancel()
        job = null
        client?.close()
        client = null
        scope.launch {
            try {
                link.disconnect()
            } catch (_: IoProblem) {
                // Already gone.
            }
        }
    }

    fun snapshot(): GoProLiveSnapshot =
        GoProLiveSnapshot(connected, error, info, statuses, polls, lastMs?.let { nowMs() - it })

    private suspend fun run() {
        while (true) {
            try {
                connectAndPoll()
            } catch (_: TimeoutCancellationException) {
                fail("GoPro not found")
            } catch (e: IoProblem) {
                fail(e.message ?: "lost")
            }
            delay(retryMillis)
        }
    }

    private suspend fun connectAndPoll() {
        withTimeout(connectTimeoutMillis) { link.connect() }
        val c = client ?: GoProClient(link, scope).also { client = it }
        connected = true
        error = null
        if (info == null) {
            info = try {
                OpenGoPro.HardwareInfo.parse(c.send(OpenGoPro.hardwareInfo()).payload)
            } catch (_: GoProException) {
                null
            }
        }
        var keptAwakeAt: Long? = null
        while (true) {
            poll(c)
            val now = nowMs()
            if (keptAwakeAt == null || now - keptAwakeAt >= OpenGoPro.KeepAliveIntervalMillis) {
                c.send(OpenGoPro.keepAlive())
                keptAwakeAt = now
            }
            delay(pollMillis)
        }
    }

    private suspend fun poll(c: GoProClient) {
        val response = try {
            c.send(OpenGoPro.statuses(*asked))
        } catch (e: GoProException) {
            // An older camera may refuse a status it does not have: ask only the basic ones from then on.
            if (asked.size == Basic.size) throw e
            asked = Basic
            c.send(OpenGoPro.statuses(*asked))
        }
        statuses = statuses + OpenGoPro.parseStatuses(response.payload).mapValues { (_, v) -> number(v) }
        polls++
        lastMs = nowMs()
    }

    private suspend fun fail(why: String) {
        connected = false
        error = why
        client?.close()
        client = null
        try {
            link.disconnect()
        } catch (_: IoProblem) {
            // Already gone.
        }
    }

    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    companion object {
        /** Status ids (open_gopro, models/constants/statuses.py, StatusId). */
        const val INTERNAL_BATTERY_BARS = 2
        const val OVERHEATING = 6
        const val BUSY = OpenGoPro.Status.BUSY
        const val ENCODING = OpenGoPro.Status.ENCODING
        const val VIDEO_ENCODING_DURATION = 13
        const val PRIMARY_STORAGE = 33
        const val REMAINING_VIDEO_TIME = 35
        const val VIDEOS = 39
        const val SD_CARD_REMAINING = 54
        const val GPS_LOCK = 68
        const val INTERNAL_BATTERY_PERCENTAGE = 70
        const val COLD = 85

        /** What the page asks each second. */
        val Statuses = intArrayOf(
            INTERNAL_BATTERY_PERCENTAGE, INTERNAL_BATTERY_BARS, ENCODING, VIDEO_ENCODING_DURATION, BUSY,
            PRIMARY_STORAGE, REMAINING_VIDEO_TIME, SD_CARD_REMAINING, VIDEOS, OVERHEATING, COLD, GPS_LOCK,
        )

        private const val ByteMask = 0xFFL

        /** What every Open GoPro camera answers. */
        private val Basic = intArrayOf(INTERNAL_BATTERY_BARS, ENCODING, BUSY, PRIMARY_STORAGE, REMAINING_VIDEO_TIME)

        /** A status value: a big-endian signed integer of its length (1, 4 or 8 bytes). */
        fun number(bytes: ByteArray): Long {
            if (bytes.isEmpty()) return 0
            var v = bytes[0].toLong() // sign-extended
            for (i in 1 until minOf(bytes.size, Long.SIZE_BYTES)) {
                v = (v shl Byte.SIZE_BITS) or (bytes[i].toLong() and ByteMask)
            }
            return v
        }
    }
}

/** The GoPro live page's text (FBBD-440), the same on Android and the iPhone, in the RaceBox diagnostics look. */
object GoProLiveText {
    const val NOTE = "Read only: the page asks the camera's status and keeps it awake while open. It never " +
        "starts recording or changes a setting. Starting a session hands the camera back."

    private const val SecondsPerMinute = 60L
    private const val MinutesPerHour = 60L
    private const val KbPerGb = 1_000_000.0
    private const val MsPerSecond = 1_000.0
    private const val Charging = 4L
    private const val FullBars = 3

    fun header(name: String, s: GoProLiveSnapshot): DiagHeaderText = when {
        s.connected && s.polls > 0 -> {
            val recording = s.statuses[GoProLive.ENCODING]?.let { it != 0L } == true
            DiagHeaderText(true, if (recording) "Connected · Recording" else "Connected", detail(name, s))
        }
        s.error != null -> DiagHeaderText(false, "Trying again", "$name · ${s.error}")
        else -> DiagHeaderText(false, "Connecting…", "$name · wake the camera if it sleeps")
    }

    private fun detail(name: String, s: GoProLiveSnapshot): String =
        listOfNotNull(name, s.info?.modelName?.takeIf { it.isNotBlank() }).distinct().joinToString(" · ")

    fun sections(s: GoProLiveSnapshot): List<DiagSection> = buildList {
        s.info?.let { i ->
            add(
                DiagSection(
                    "Camera",
                    listOfNotNull(
                        i.modelName.takeIf { it.isNotBlank() }?.let { DiagRow("Model", it) },
                        i.firmware.takeIf { it.isNotBlank() }?.let { DiagRow("Firmware", it) },
                        i.serial.takeIf { it.isNotBlank() }?.let { DiagRow("Serial number", it) },
                    ),
                ),
            )
        }
        val st = s.statuses
        section("Battery") {
            st[GoProLive.INTERNAL_BATTERY_PERCENTAGE]?.let { add(DiagRow("Charge", "$it %")) }
            st[GoProLive.INTERNAL_BATTERY_BARS]?.let {
                add(DiagRow("Bars", if (it == Charging) "Charging" else "$it of $FullBars"))
            }
        }
        section("SD card") {
            st[GoProLive.PRIMARY_STORAGE]?.let { add(DiagRow("Card", storage(it))) }
            st[GoProLive.REMAINING_VIDEO_TIME]?.let { add(DiagRow("Video left", hoursMinutes(it))) }
            st[GoProLive.SD_CARD_REMAINING]?.let { add(DiagRow("Free space", "${fixed(it / KbPerGb, 1)} GB")) }
            st[GoProLive.VIDEOS]?.let { add(DiagRow("Videos on the card", it.toString())) }
        }
        section("Camera state") {
            st[GoProLive.ENCODING]?.let { on ->
                val length = st[GoProLive.VIDEO_ENCODING_DURATION]?.takeIf { on != 0L }
                add(DiagRow("Recording", yesNo(on) + length?.let { " · ${minutesSeconds(it)}" }.orEmpty()))
            }
            st[GoProLive.BUSY]?.let { add(DiagRow("Busy", yesNo(it))) }
            st[GoProLive.OVERHEATING]?.let { add(DiagRow("Too hot", yesNo(it))) }
            st[GoProLive.COLD]?.let { add(DiagRow("Too cold", yesNo(it))) }
            st[GoProLive.GPS_LOCK]?.let { add(DiagRow("Camera GPS", if (it != 0L) "Locked" else "No lock")) }
        }
        add(
            DiagSection(
                "Link",
                listOfNotNull(
                    DiagRow("Status answers", s.polls.toString()),
                    s.sinceLastMs?.let { DiagRow("Last answer", "${fixed(it / MsPerSecond, 1)} s ago") },
                ),
            ),
        )
    }

    private fun MutableList<DiagSection>.section(title: String, rows: MutableList<DiagRow>.() -> Unit) {
        val list = mutableListOf<DiagRow>().apply(rows)
        if (list.isNotEmpty()) add(DiagSection(title, list))
    }

    private fun yesNo(v: Long) = if (v != 0L) "Yes" else "No"

    /** Primary storage status (open_gopro PrimaryStorage). */
    private fun storage(v: Long): String = StorageNames[v] ?: "Unknown"

    private val StorageNames = mapOf(
        0L to "OK",
        1L to "Full",
        2L to "Removed",
        3L to "Format error",
        4L to "Busy",
        8L to "Swapped",
    )

    private fun hoursMinutes(seconds: Long): String {
        val minutes = seconds / SecondsPerMinute
        val h = minutes / MinutesPerHour
        val m = minutes % MinutesPerHour
        return if (h > 0) "$h h ${m.toString().padStart(2, '0')} min" else "$m min"
    }

    private fun minutesSeconds(seconds: Long): String =
        "${seconds / SecondsPerMinute}:${(seconds % SecondsPerMinute).toString().padStart(2, '0')}"
}
