package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.SessionFiles
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sessions as older builds stored them. meta.properties is "format=1" in every build in this
 * repository's history (#51 on); FBBD-164 only added the optional "imported." keys, so a session
 * from 0.3.1 has none. The chunk and end-marker formats have not changed (FramesTest).
 */
class StoredFormatTest {
    /** meta.properties of a session 0.3.1 recorded with a RaceBox at a saved track. */
    private val meta031 = """
        #Sat Sep 12 10:00:00 UTC 2026
        format=1
        id=20260912-100000-a1b2
        startUtcMillis=1789207200000
        car=Corolla
        track=Tor Pozna\u0144 \u00B7 Full
        devices=RaceBox Mini S
        startFinish.name=Start / Finish
        startFinish.lat1=52.417
        startFinish.lon1=16.8048
        startFinish.lat2=52.417
        startFinish.lon2=16.8052
        channels=2
        channel.0.id=2
        channel.0.name=gnss_lat
        channel.0.unit=deg
        channel.0.source=gnss
        channel.0.decimals=8
        channel.1.id=101
        channel.1.name=gnss_hacc
        channel.1.unit=m
        channel.1.source=racebox
        channel.1.decimals=2
    """.trimIndent()

    @Test
    fun `reads session metadata as 0_3_1 wrote it`() {
        val props = Properties().apply { load(meta031.byteInputStream(Charsets.ISO_8859_1)) }
        val info = SessionFiles.propsToMeta(props)
        assertEquals(1_789_207_200_000L, info.startUtcMillis)
        assertEquals("Corolla", info.car)
        assertEquals("Tor Poznań · Full", info.track)
        assertEquals(listOf("RaceBox Mini S"), info.devices)
        assertEquals(Gate("Start / Finish", 52.417, 16.8048, 52.417, 16.8052), info.startFinish)
        assertEquals(
            listOf(ChannelDef(2, "gnss_lat", "deg", "gnss", 8), ChannelDef(101, "gnss_hacc", "m", "racebox", 2)),
            info.channels,
        )
        assertNull(info.imported)
    }

    @Test
    fun `a recorded session is written with the keys 0_3_1 reads`() {
        val old = Properties().apply { load(meta031.byteInputStream(Charsets.ISO_8859_1)) }
        val info = SessionFiles.propsToMeta(old)
        val written = SessionFiles.metaToProps(old.getProperty("id"), info)
        // Each channel's provenance is added (FBBD-174); 0.3.1 ignores keys it does not know.
        val added = written.stringPropertyNames() - old.stringPropertyNames()
        assertEquals(setOf("channel.0.provenance", "channel.1.provenance"), added)
        added.forEach { written.remove(it) }
        assertEquals(old, written)
    }

    @Test
    fun `a 0_3_1 session gets its channels' provenance from their source`() {
        val props = Properties().apply { load(meta031.byteInputStream(Charsets.ISO_8859_1)) }
        val info = SessionFiles.propsToMeta(props)
        // A RaceBox's position and its accuracy figure: both measured by the receiver.
        val receiver = Provenance.MEASURED_GNSS_RECEIVER
        assertEquals(listOf(receiver, receiver), info.channels.map { it.provenance })
    }
}
