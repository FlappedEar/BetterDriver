package com.flappedear.betterdriver.sessionanalysis

import android.app.Application
import com.flappedear.betterdriver.feature.track.editor.TileLoader
import com.flappedear.betterdriver.timing.map.TileSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The photo under the perfect lap in 3D (FBBD-316, FBBD-412): which source, and what the saved video keeps. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SatelliteGroundTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val loader by lazy { TileLoader(folder.root, "test") }

    @After
    fun close() = loader.close()

    @Test
    fun `without a key the road is drawn`() {
        assertNull(satelliteGround(loader))
    }

    @Test
    fun `a MapTiler key gives MapTiler's photo and its credit`() {
        loader.updateMapTilerKey("m")
        val ground = satelliteGround(loader)
        assertEquals(TileSources.mapTiler("").attribution, ground?.attribution)
    }

    @Test
    fun `a saved video over Google's photo draws the road without a MapTiler key, as Google's terms keep no copy`() {
        val google = TileSources.googleSatellite("k", "s")
        val ground = SatelliteGround(loader, google, loader.credit(google))
        assertEquals("Google Maps", ground.attribution)
        assertNull(ground.load(18, 144_248, 88_303))
        assertNull(ground.video)
        assertEquals(TileSources.videoNote(google.id, null), ground.videoNote)
    }

    @Test
    fun `a saved video over Google's photo takes MapTiler's with its credit once its key is entered`() {
        val google = TileSources.googleSatellite("k", "s")
        val ground = SatelliteGround(loader, google, loader.credit(google), TileSources.forVideo("m"))
        assertEquals(TileSources.mapTiler("").attribution, ground.video?.attribution)
        assertTrue(ground.videoNote.orEmpty().startsWith("The video shows MapTiler's photo"))
    }

    @Test
    fun `over MapTiler's photo the video keeps the player's ground and says nothing`() {
        val mapTiler = TileSources.mapTiler("m")
        val ground = SatelliteGround(loader, mapTiler)
        assertSame(ground, ground.video)
        assertNull(ground.videoNote)
    }
}
