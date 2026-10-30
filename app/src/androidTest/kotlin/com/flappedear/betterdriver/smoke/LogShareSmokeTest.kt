package com.flappedear.betterdriver.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flappedear.betterdriver.debug.DebugLogShare
import com.flappedear.betterdriver.recorder.log.DebugLog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/**
 * Settings › Diagnostics › Share log as Arek sends it after a drive (FBBD-146, FBBD-396): the zip
 * holds the device line, the continuous log with the line just written, and the app's logcat.
 */
@RunWith(AndroidJUnit4::class)
class LogShareSmokeTest {
    @Test
    fun theSharedZipHoldsTheLogJustWritten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = "log share smoke ${System.nanoTime()}"
        DebugLog.i("LogShareSmoke", marker)
        val zip = DebugLogShare.prepare(context)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue("no device.txt in $names", "device.txt" in names)
            assertTrue("no logcat.txt in $names", "logcat.txt" in names)
            val logs = names.filter { it.endsWith(".log") }
            assertTrue("no log in $names", logs.isNotEmpty())
            val text = logs.joinToString("\n") { name ->
                z.getInputStream(z.getEntry(name)).use { it.readBytes().decodeToString() }
            }
            assertTrue("the line just written is not in the log", marker in text)
            val device = z.getInputStream(z.getEntry("device.txt")).use { it.readBytes().decodeToString() }
            assertTrue("device.txt is empty", device.isNotBlank())
        }
    }
}
