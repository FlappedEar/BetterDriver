package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.analysis.review.reviewOf
import com.flappedear.betterdriver.analysis.review.reviewSession
import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.feature.analysis.DrivingStateKeys
import com.flappedear.betterdriver.feature.analysis.MapColoring
import com.flappedear.betterdriver.feature.analysis.coastingText
import com.flappedear.betterdriver.feature.analysis.drivingStatesLabel
import com.flappedear.betterdriver.feature.analysis.deltaRateText
import com.flappedear.betterdriver.feature.analysis.focusSeconds
import com.flappedear.betterdriver.feature.analysis.mapScale
import com.flappedear.betterdriver.feature.analysis.mapStrokes
import com.flappedear.betterdriver.feature.analysis.pointAtDistance
import com.flappedear.betterdriver.feature.analysis.ggRingText
import com.flappedear.betterdriver.feature.analysis.lapsText
import com.flappedear.betterdriver.feature.analysis.lineUi
import com.flappedear.betterdriver.feature.analysis.timeGoesHeading
import com.flappedear.betterdriver.feature.analysis.whereToFindTimeLink
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iPad review's numbers for a session (FBBD-331) are the same as DemoParityTest's on every platform. */
class SessionReviewTest {
    private val track = TrackJson.decode(demoAsset("jastrzab.track.json"))

    private fun demo(): SessionData {
        val replay = SessionReplay.read(demoAsset("jastrzab.replay"), StartUtcMillis, RateHz)
        val loopFixes = demoAsset("jastrzab.replay").lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble().times(RateHz).toLong()
        val sets = (0 until loopFixes).map { SampleSet(it * MicrosPerSecond / RateHz, replay.fix(it)) }
        val info = SessionInfo(StartUtcMillis, channels = GnssChannels.ALL)
        return SessionData(
            "jastrzab", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(),
            null, 0, 0,
        )
    }

    @Test
    fun `the demo session is found at its track by position and reviewed as on Android`() {
        val review = reviewOf(demo(), listOf(track))
        assertEquals(track.id, review.track?.id)
        assertEquals(23, review.laps.size)
        assertEquals(18, review.laps.single { it.fastest }.number)
        assertEquals("109.906", fixed(review.bestLapSeconds!!, 3))
        assertEquals("108.201", fixed(review.theoreticalBestSeconds!!, 3))
        assertEquals("0.000", fixed(review.laps.single { it.fastest }.deltaSeconds, 3))
        // The same top 3 as DemoParityTest's line plan focus, from the Map tab's ranked corners.
        val line = checkNotNull(review.line)
        assertEquals(
            listOf("T11:0.281", "T1:0.112"),
            line.ranked.take(2).map { "${it.tag}:${fixed(it.gainSeconds, 3)}" },
        )
    }

    @Test
    fun `the review keeps the line plan it shows for the next session's coach`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val plan = checkNotNull(reviewed.linePlan)
        assertEquals(track.id, plan.trackId)
        assertEquals("jastrzab", plan.sessionId)
        assertEquals(reviewed.review.line, lineUi(plan, "jastrzab"))
    }

    @Test
    fun `compare opens on the second fastest lap against the fastest by distance`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val compare = checkNotNull(reviewed.defaultCompare())
        val laps = reviewed.review.laps
        val second = laps.filter { it.counts && !it.fastest }.minBy { it.seconds }
        assertEquals(18, compare.referenceNumber)
        assertEquals(second.number, compare.otherNumber)
        assertEquals(19, compare.otherNumber)
        assertEquals("2027", fixed(compare.lengthMeters, 0))
        assertEquals(12, compare.corners.size)
        // At the line the delta is the gap between the two lap times.
        assertEquals(fixed(second.deltaSeconds, 2), fixed(compare.delta.last().last().value, 2))
        assertEquals(compare, reviewed.compare(compare.otherIndex))
        // Android's screen model, for the Corners and Car tabs: the same laps and corners.
        val screen = checkNotNull(reviewed.defaultScreen())
        assertEquals(19, screen.compare?.otherNumber)
        assertEquals(12, screen.corners.size)
        assertEquals(screen.sectors.size, screen.sectors.count { it.bestSeconds != null })
        assertEquals("108.201", fixed(screen.theoreticalMillis!! / 1000.0, 3))
        assertTrue(compare.corners.isNotEmpty())
        assertTrue(compare.referenceSpeed.isNotEmpty() && compare.otherSpeed.isNotEmpty())
    }

    @Test
    fun `the video review plays the fastest and the compared lap from the session's clip index`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val index = """{"version":1,"clips":[
            {"fileName":"p.mp4","kind":"PHONE","sourceId":"phone","sessionOffsetUs":0,
             "alignment":{"method":"CAPTURE_CLOCK","uncertaintyUs":0}},
            {"fileName":"g.mp4","kind":"GOPRO","sourceId":"gopro","sessionOffsetUs":0,
             "alignment":{"method":"GPS_MATCH","uncertaintyUs":0},"status":"ON_CAMERA"}]}"""
        val clips = indexedClips(index)
        assertEquals(listOf("p.mp4"), clips.map { it.file })
        val other = checkNotNull(reviewed.defaultCompare()).otherIndex
        val video = checkNotNull(reviewed.videoReview(other, clips, "v") { it == "v/p.mp4" })
        assertEquals("109.906", fixed(video.a.timeMillis / 1000.0, 3))
        assertEquals(reviewed.review.laps[other].seconds, video.b.timeMillis / 1000.0, 0.01)
        assertTrue(video.a.hasVideo && video.b.hasVideo)
        val start = checkNotNull(video.a.videoAt(0))
        assertEquals("v/p.mp4", video.a.path(start))
        assertEquals(video.a.trace.lap.startMicros / 1000, start.mediaMillis)
        // Without the file there is no video, but the laps still compare.
        val none = checkNotNull(reviewed.videoReview(other, clips, "v") { false })
        assertNull(none.a.videoAt(0))
        assertEquals(video.traces.lengthMicros, none.traces.lengthMicros)
        assertEquals(emptyList(), indexedClips("not json"))
    }

    @Test
    fun `the summary has what to work on next and where time goes and the whole session's G-forces as on Android`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val screen = checkNotNull(reviewed.defaultScreen())
        // What to work on next (FBBD-20): one primary corner first, at most two more.
        assertTrue(screen.focus.isNotEmpty() && screen.focus.size <= 3)
        assertTrue(screen.focus.first().primary)
        assertTrue(screen.gains.isNotEmpty())
        assertEquals("Best actual lap 18 against the best of each corner", timeGoesHeading(screen))
        // The whole session's G-forces, here from GPS speed and heading: the demo has no accelerometer.
        val g = checkNotNull(reviewed.whole.gForces)
        assertEquals(g, screen.gForces)
        assertEquals(GgSource.Gps, g.source)
        assertEquals(reviewed.whole.acceleration, screen.acceleration)
        // A session away from every saved track still has its G-forces.
        assertEquals(g.points.size, checkNotNull(reviewSession(demo(), emptyList()).whole.gForces).points.size)
    }

    @Test
    fun `the summary's words are Android's`() {
        assertEquals("laps 2, 4 and 7", lapsText(listOf(2, 4, 7)))
        assertEquals("0.18 s", focusSeconds(0.181))
        assertEquals("Where to find time ≈ 0.41 s ›", whereToFindTimeLink(0.41))
        assertEquals("Where to find time ›", whereToFindTimeLink(0.0))
        assertEquals("1.5 g", ggRingText(1.5))
    }

    @Test
    fun `every pass through a corner has its traces and clip and opens two laps on video as on Android`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val clips = indexedClips(
            """{"version":1,"clips":[{"fileName":"p.mp4","kind":"PHONE","sourceId":"phone","sessionOffsetUs":0}]}""",
        )
        val corner = checkNotNull(reviewed.defaultScreen()).corners.first().id
        val ui = checkNotNull(reviewed.everyPass(corner, clips, "v") { it == "v/p.mp4" })
        assertEquals(corner, ui.cornerId)
        assertTrue(ui.rows.size > 1)
        assertTrue(ui.rows.all { it.reviewable })
        assertTrue(ui.rows.first().best)
        assertEquals("v/p.mp4", ui.rows.first().video?.path)
        assertTrue(checkNotNull(ui.traces).passes.isNotEmpty())
        // Without the file the passes are still listed, with no video.
        assertTrue(checkNotNull(reviewed.everyPass(corner, clips, "v") { false }).rows.none { it.video != null })
        // Two ticked passes open in the lap review, the faster through the corner first, cued before it.
        val (a, b) = ui.rows.take(2).map { it.lapIndex }
        val video = checkNotNull(reviewed.lapsOnVideo(a, b, clips, "v") { it == "v/p.mp4" })
        assertEquals(reviewed.review.laps[a].seconds, video.a.timeMillis / 1000.0, 0.01)
        assertEquals(reviewed.review.laps[b].seconds, video.b.timeMillis / 1000.0, 0.01)
        assertTrue(checkNotNull(reviewed.reviewStart(corner)) in 0.0..1.0)
    }

    @Test
    fun `the Map tab's Laps view colours the compared lap against the best as on Android`() {
        val reviewed = reviewSession(demo(), listOf(track))
        val map = checkNotNull(checkNotNull(reviewed.defaultScreen()).map)
        assertEquals(19, map.lapNumber)
        assertEquals(18, map.referenceNumber)
        val delta = mapScale(map.map, MapColoring.Delta)
        val strokes = mapStrokes(map.map, delta, Gain, Neutral, Loss)
        assertTrue(strokes.isNotEmpty())
        assertTrue(strokes.any { it.argb != Neutral })
        assertTrue(checkNotNull(mapScale(map.map, MapColoring.Speed).speed).high > 0.0)
        val at = checkNotNull(pointAtDistance(map.map, 500.0))
        assertTrue(kotlin.math.abs(at.distanceMeters - 500.0) < 50.0)
        assertEquals("Gaining 0.12 s per 100 m here", deltaRateText(-0.123))
        assertEquals("Losing 0.30 s per 100 m here", deltaRateText(0.3))
    }

    @Test
    fun `Compare has what the car is doing along both laps with its key and coasting as on Android`() {
        val compare = checkNotNull(checkNotNull(reviewSession(demo(), listOf(track)).defaultScreen()).compare)
        assertEquals(listOf("Brake", "Coast", "Corner", "Throttle", "Flat out"), DrivingStateKeys.map { it.name })
        assertTrue(drivingStatesLabel(compare).startsWith("What the car is doing"))
        val measured = compare.copy(
            referenceCoastingSeconds = 1.32,
            otherCoastingSeconds = 2.054,
            statesProvenance = null,
        )
        assertEquals("Coasting: lap 18 1.32 s · lap 19 2.05 s", coastingText(measured))
        assertNull(coastingText(measured.copy(referenceCoastingSeconds = null, otherCoastingSeconds = null)))
    }

    @Test
    fun `a session away from every saved track has no laps`() {
        val review = reviewOf(demo(), emptyList())
        assertNull(review.track)
        assertEquals(emptyList(), review.laps)
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
        val Gain = 0xFF22D37A.toInt()
        val Neutral = 0xFF8C9AA5.toInt()
        val Loss = 0xFFFF6863.toInt()
    }
}
