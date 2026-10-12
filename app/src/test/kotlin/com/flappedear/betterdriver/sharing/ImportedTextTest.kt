package com.flappedear.betterdriver.sharing

import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.shell.importedFrom
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.transfer.foreign.ForeignFormat
import com.flappedear.betterdriver.transfer.foreign.ImportedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The messages and card line of an import from another app (FBBD-164). */
class ImportedTextTest {
    private val at = GeoPoint(52.417, 16.813)
    private val line = TrackLine(GeoPoint(52.418, 16.813), GeoPoint(52.4178, 16.813))

    private fun made(detection: TrackDetection, skipped: Int = 0) = ImportedSession(
        id = "20261012-104200-abcdef12",
        info = SessionInfo(0, imported = ImportOrigin("VBO", "a.vbo")),
        format = ForeignFormat.VBO,
        points = 1500,
        skipped = skipped,
        durationMicros = 60_000_000,
        detection = detection,
    )

    @Test
    fun namesTheTrackItWasTimedAt() {
        val track = Track("t", "Tor Poznań", at, startFinish = line)
        assertEquals(
            "Imported the VBO session of 12 Oct 10:42 at Tor Poznań: 1500 positions.",
            importedText(made(TrackDetection.Saved(track, 10.0)), "12 Oct 10:42"),
        )
    }

    @Test
    fun saysWhyThereAreNoLaps() {
        val noLine = Track("t", "Tor Poznań", at)
        assertEquals(
            "Imported the VBO session of x at Tor Poznań (no start/finish line yet, so no laps): 1500 positions.",
            importedText(made(TrackDetection.Saved(noLine, 10.0)), "x"),
        )
        val circuit = Circuit("tp", "Tor Poznań", "PL", at)
        assertEquals(
            "Imported the VBO session of x at Tor Poznań (not one of your tracks yet, so no laps): 1500 positions. " +
                "2 rows could not be read and were left out.",
            importedText(made(TrackDetection.Directory(circuit, 10.0), skipped = 2), "x"),
        )
        assertEquals(
            "Imported the VBO session of x: 1500 positions. 1 row could not be read and was left out.",
            importedText(made(TrackDetection.Unknown, skipped = 1), "x"),
        )
    }

    @Test
    fun cardLineNamesFileAndFormat() {
        assertEquals("From a.vbo (VBO)", importedFrom(SessionInfo(0, imported = ImportOrigin("VBO", "a.vbo"))))
        assertEquals("From a GPX file", importedFrom(SessionInfo(0, imported = ImportOrigin("GPX", ""))))
        assertNull(importedFrom(SessionInfo(0)))
    }
}
