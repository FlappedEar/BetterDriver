@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deleted track takes its coach files with it, on Android and iOS alike (TrackCoachFiles). */
class TrackCoachFilesTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val dir = Files.createTempDirectory("track-coach-files").toFile()
    private val coach = File(dir, "coach").path

    private fun reference(trackId: String): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { SimCircuit.Pace() }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps)))).copy(trackId = trackId)
    }

    private fun focus(trackId: String) = SessionFocusRecord(
        trackId = trackId,
        sessionId = "s1",
        sessionStartUtcMillis = 1_000L,
        algorithm = "test",
        targets = emptyList(),
    )

    private fun keep(trackId: String) {
        CoachReferenceStore("$coach/references").save(reference(trackId))
        CoachPicksStore("$coach/picks").save(trackId, listOf(CornerPick("Corner 1", listOf(50.0), listOf(19.0), "s1")))
        SessionFocusStore("$coach/focus").save(focus(trackId))
    }

    @Test
    fun deletingATrackDeletesItsCoachFilesAndLeavesOtherTracksAlone() {
        keep("gone")
        keep("kept")
        val failures = mutableListOf<String>()

        assertTrue(TrackCoachFiles(coach).delete("gone") { what, _ -> failures += what })

        assertEquals(emptyList(), failures)
        assertNull(CoachReferenceStore("$coach/references").get("gone"))
        assertTrue(CoachPicksStore("$coach/picks").get("gone").isEmpty())
        assertNull(SessionFocusStore("$coach/focus").get("gone", "s1"))
        assertNotNull(CoachReferenceStore("$coach/references").get("kept"))
        assertEquals(1, CoachPicksStore("$coach/picks").get("kept").size)
        assertNotNull(SessionFocusStore("$coach/focus").get("kept", "s1"))
        dir.deleteRecursively()
    }

    @Test
    fun aTrackWithNoCoachFilesIsAlreadyClean() {
        assertTrue(TrackCoachFiles(coach).delete("never"))
        dir.deleteRecursively()
    }

    @Test
    fun aBadTrackIdIsNotDeletedAndSaysWhatFailed() {
        val failures = mutableListOf<String>()
        assertFalse(TrackCoachFiles(coach).delete("../escape") { what, _ -> failures += what })
        assertTrue(failures.isNotEmpty())
        dir.deleteRecursively()
    }
}
