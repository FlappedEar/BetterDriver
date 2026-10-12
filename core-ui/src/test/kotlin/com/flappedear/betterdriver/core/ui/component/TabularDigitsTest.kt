package com.flappedear.betterdriver.core.ui.component

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.numberStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Values never jump (FBBD-39, FBBD-62): a tile showing "9.99" and then "10.00"
 * keeps every digit in a cell of the same width, although the number font has
 * proportional digits.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xhdpi")
class TabularDigitsTest {
    @Before
    fun registerActivity() {
        // The library has no manifest entry for the activity the compose test hosts its content in.
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    @Test
    fun tileKeepsFixedWidthDigitsFrom9p99To10p00() = runComposeUiTest {
        var value by mutableStateOf("9.99")
        setContent {
            BetterDriverTheme(track = true) {
                Column {
                    ValueTile("G", value, "g", Modifier.size(width = 200.dp, height = 80.dp))
                    // The same strings drawn as plain text, to show the font's digits differ in width.
                    Text("1.11", Modifier.testTag("plain-ones"), style = numberStyle(34.dp, FontWeight.SemiBold))
                    Text("9.99", Modifier.testTag("plain-nines"), style = numberStyle(34.dp, FontWeight.SemiBold))
                }
            }
        }
        val plainOnes = onNodeWithTag("plain-ones").fetchSemanticsNode().size.width
        val plainNines = onNodeWithTag("plain-nines").fetchSemanticsNode().size.width
        assertNotEquals("the number font has proportional digits", plainOnes, plainNines)

        // Read sizes at once: a semantics node reports its current layout, not a copy.
        val ninesWidth = number("9.99").size.width
        val ninesLeft = number("9.99").boundsInRoot.left
        value = "10.00"
        val tenWidth = number("10.00").size.width
        // The value grows to the right from the same place, by one digit cell.
        assertEquals(ninesLeft, number("10.00").boundsInRoot.left, 0f)
        val cell = tenWidth - ninesWidth

        // Every value of the same shape is as wide, whatever its digits.
        listOf("0.00", "1.11", "4.71", "8.88").forEach { v ->
            value = v
            assertEquals("width of $v", ninesWidth, number(v).size.width)
        }
        listOf("11.11", "99.99", "47.10").forEach { v ->
            value = v
            assertEquals("width of $v", tenWidth, number(v).size.width)
        }
        value = "100.00"
        assertEquals("one more digit adds one cell", tenWidth + cell, number("100.00").size.width)
    }

    private fun ComposeUiTest.number(text: String): SemanticsNode {
        waitForIdle()
        return onNodeWithContentDescription(text).fetchSemanticsNode()
    }
}
