package com.flappedear.betterdriver.feature.track.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Google key dialog (FBBD-412) names what a key limited to this Android app needs. */
class GoogleKeyNoteTest {
    @Test
    fun `the note names the package and the certificate's SHA-1 with colons`() {
        val note = googleKeyNote(mapOf(AndroidPackageHeader to "com.example", AndroidCertHeader to "0A1B2C"))
        assertEquals(
            "Turns on Google's satellite map (a Map Tiles API key). The key stays on this device. " +
                "Limit the key to the Map Tiles API and to Android apps: package com.example, SHA-1 0A:1B:2C.",
            note,
        )
    }

    @Test
    fun `without the certificate the note leaves the limit out`() {
        assertEquals(
            "Turns on Google's satellite map (a Map Tiles API key). The key stays on this device.",
            googleKeyNote(mapOf(AndroidPackageHeader to "com.example")),
        )
    }
}
