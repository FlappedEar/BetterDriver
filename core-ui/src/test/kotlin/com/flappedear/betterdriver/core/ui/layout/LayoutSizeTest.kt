package com.flappedear.betterdriver.core.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSizeTest {
    @Test
    fun widthBreakpoints() {
        assertEquals(WidthClass.Compact, WidthClass.of(411f))
        assertEquals(WidthClass.Compact, WidthClass.of(599.9f))
        assertEquals(WidthClass.Medium, WidthClass.of(600f))
        assertEquals(WidthClass.Medium, WidthClass.of(800f))
        assertEquals(WidthClass.Expanded, WidthClass.of(840f))
        assertEquals(WidthClass.Expanded, WidthClass.of(1280f))
    }

    @Test
    fun heightBreakpoints() {
        assertEquals(HeightClass.Compact, HeightClass.of(411f))
        assertEquals(HeightClass.Medium, HeightClass.of(480f))
        assertEquals(HeightClass.Medium, HeightClass.of(800f))
        assertEquals(HeightClass.Expanded, HeightClass.of(900f))
    }

    @Test
    fun tabletNeedsAnExpandedWidthAndRoomInHeight() {
        // Galaxy Tab held sideways (1280 x 800 dp) and upright (800 x 1280 dp).
        assertTrue(size(1280f, 800f).tablet)
        assertFalse(size(800f, 1280f).tablet)
        // A phone upright, and a large phone held sideways: wide, but short.
        assertFalse(size(411f, 914f).tablet)
        assertFalse(size(914f, 411f).tablet)
        assertFalse(LayoutSize.Phone.tablet)
    }

    @Test
    fun sideBySideOnTabletsAndLargePhonesHeldSideways() {
        assertTrue(size(1280f, 800f).sideBySide)
        assertTrue(size(800f, 1280f).sideBySide)
        assertTrue(size(914f, 411f).sideBySide)
        // A small phone held sideways keeps the column, as does a phone upright.
        assertFalse(size(732f, 411f).sideBySide)
        assertFalse(size(411f, 914f).sideBySide)
    }

    @Test
    fun paneKeepsTheWindowHeight() {
        val pane = size(1280f, 800f).pane(788f)
        assertEquals(WidthClass.Medium, pane.width)
        assertEquals(HeightClass.Medium, pane.height)
        assertFalse(pane.tablet)
        assertTrue(pane.sideBySide)
        assertEquals(WidthClass.Compact, size(1280f, 800f).pane(440f).width)
    }

    private fun size(width: Float, height: Float) = LayoutSize(WidthClass.of(width), HeightClass.of(height))
}
