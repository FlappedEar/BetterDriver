package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveLayoutTest {
    private val tabletWide = size(1280f, 800f)
    private val tabletUpright = size(800f, 1280f)
    private val phone = size(411f, 914f)
    private val phoneSideways = size(914f, 411f)

    @Test
    fun railOnATabletBarOnAPhone() {
        assertEquals(AppNavigation.Rail, appNavigation(tabletWide, topLevel = true))
        assertEquals(AppNavigation.Bar, appNavigation(tabletUpright, topLevel = true))
        assertEquals(AppNavigation.Bar, appNavigation(phone, topLevel = true))
        assertEquals(AppNavigation.Bar, appNavigation(phoneSideways, topLevel = true))
    }

    @Test
    fun noBarOrRailAwayFromTheTopLevelScreens() {
        // The track screen, the review and the analysis keep the whole window.
        assertEquals(AppNavigation.None, appNavigation(tabletWide, topLevel = false))
        assertEquals(AppNavigation.None, appNavigation(phone, topLevel = false))
    }

    @Test
    fun sessionsPanes() {
        assertEquals(SessionsPanes.ListAndDetail, sessionsPanes(tabletWide, shown = null))
        assertEquals(SessionsPanes.ListAndDetail, sessionsPanes(tabletWide, shown = "a"))
        assertEquals(SessionsPanes.List, sessionsPanes(phone, shown = null))
        assertEquals(SessionsPanes.List, sessionsPanes(phoneSideways, shown = null))
        // Turned upright with a session open, the tablet keeps showing it.
        assertEquals(SessionsPanes.Detail, sessionsPanes(tabletUpright, shown = "a"))
        assertEquals(SessionsPanes.List, sessionsPanes(tabletUpright, shown = null))
        // The list beside a session hidden: the session alone; with nothing picked the list shows.
        assertEquals(SessionsPanes.Detail, sessionsPanes(tabletWide, shown = "a", listHidden = true))
        assertEquals(SessionsPanes.ListAndDetail, sessionsPanes(tabletWide, shown = null, listHidden = true))
        assertEquals(SessionsPanes.List, sessionsPanes(phone, shown = null, listHidden = true))
    }

    @Test
    fun aDeletedSessionIsNoLongerShown() {
        assertEquals("a", shownSession("a", listOf("a", "b")))
        assertNull(shownSession("c", listOf("a", "b")))
        assertNull(shownSession(null, listOf("a")))
    }

    @Test
    fun listWidth() {
        // Galaxy Tab held sideways, beside the rail: the list is as wide as a phone.
        assertEquals(412f, sessionListWidthDp(1200f), 0.01f)
        assertEquals(400f, sessionListWidthDp(1000f), 0.01f)
        // At the narrowest two-pane window the list keeps room for a card's buttons.
        assertEquals(320f, sessionListWidthDp(760f), 0.01f)
    }

    @Test
    fun cueColumns() {
        assertEquals(1, cueColumns(phone))
        assertEquals(1, cueColumns(size(732f, 411f)))
        assertEquals(2, cueColumns(phoneSideways))
        assertEquals(2, cueColumns(tabletUpright))
        assertEquals(3, cueColumns(tabletWide))
        assertEquals(1, cueColumns(LayoutSize.Phone))
    }

    private fun size(width: Float, height: Float) = LayoutSize(WidthClass.of(width), HeightClass.of(height))
}
