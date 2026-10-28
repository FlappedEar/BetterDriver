package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.LiveVideoSource
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoSessionContext
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.VideoSourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * An Open GoPro camera as one of the session's [LiveVideoSource]s on Android: [OpenGoProCamera],
 * shared with the iPhone, with the recording note kept in the session's [GoProRecordings]. [stop]
 * returns no clips: the files stay on the camera and are downloaded after the session
 * ([GoProDownloader]).
 */
@Suppress("LongParameterList") // Clock and timeouts are test hooks.
class OpenGoProSource(
    override val id: String,
    link: GoProLink,
    scope: CoroutineScope,
    /** The phone's wall clock and time zone, used to set the camera's clock. */
    clock: Clock = Clock.systemDefaultZone(),
    /** The resolution and frame rate to set before recording, or null to keep the camera's own. */
    video: GoProVideo? = null,
    connectTimeoutMillis: Long = 20_000,
    readyTimeoutMillis: Long = 10_000,
    /** Where the recording note is written; the app passes Dispatchers.IO, as [scope] is the main thread (FBBD-41). */
    private val io: CoroutineContext = EmptyCoroutineContext,
    /** Told when the camera acknowledged Set Shutter on or off, or failed to, for the drive's log (FBBD-82). */
    acks: (GoProAck) -> Unit = {},
) : LiveVideoSource {
    override val kind = VideoSourceKind.GOPRO

    private val camera = OpenGoProCamera(
        id, link, scope, JavaPhoneClock(clock), video, connectTimeoutMillis, readyTimeoutMillis, acks,
    )

    override val state: StateFlow<VideoSourceState> get() = camera.state

    override suspend fun start(session: VideoSessionContext) {
        camera.start(session.sessionClock) { r -> withContext(io) { GoProRecordings(session.videoDir).put(r) } }
    }

    override suspend fun stop(): List<VideoClip> {
        camera.stop()
        return emptyList()
    }
}

/** [PhoneClock] from a java.time clock and its zone. */
class JavaPhoneClock(private val clock: Clock) : PhoneClock {
    override fun nowMillis(): Long = clock.millis()

    override fun local(utcMillis: Long): CameraDateTime {
        val t = Instant.ofEpochMilli(utcMillis).atZone(clock.zone).toLocalDateTime()
        return CameraDateTime(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    }

    override fun utcOffsetMinutes(utcMillis: Long): Int =
        clock.zone.rules.getOffset(Instant.ofEpochMilli(utcMillis)).totalSeconds / SecondsPerMinute

    override fun daylightSaving(utcMillis: Long): Boolean =
        clock.zone.rules.isDaylightSavings(Instant.ofEpochMilli(utcMillis))

    private companion object {
        const val SecondsPerMinute = 60
    }
}
