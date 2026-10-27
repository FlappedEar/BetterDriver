package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.metaEntries
import com.flappedear.betterdriver.recorder.store.metaFromEntries
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionMetaTest {
    private val info = SessionInfo(
        startUtcMillis = 1_790_000_000_123L,
        car = "FR-S = 2013: ąę",
        track = "track-1",
        devices = listOf("RaceBox Micro 1234", "Pixel 6"),
        startFinish = Gate("S/F", 50.1, 19.2, 50.10001, 19.20002),
        channels = GnssChannels.ALL + ChannelDef(40, "obd_rpm", "rpm", "obd", 0),
        imported = ImportOrigin("vbo", "a b.vbo", "VBOX", 20.0),
    )

    @Test
    fun metadataRoundTripsThroughThePropertiesFile() {
        val back = metaFromEntries(parseProperties(writeProperties(metaEntries("id-1", info))))
        assertEquals(info, back)
    }

    @Test
    fun chunkNamesAreZeroPadded() {
        assertEquals("chunk-000001.bdr", SessionFileNames.chunkName(1))
        assertEquals("chunk-1234567.bdr", SessionFileNames.chunkName(1_234_567))
    }
}
