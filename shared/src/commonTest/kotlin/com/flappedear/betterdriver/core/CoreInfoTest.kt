package com.flappedear.betterdriver.core

import kotlin.test.Test
import kotlin.test.assertTrue

class CoreInfoTest {
    @Test
    fun versionIsSemantic() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(CoreInfo.version), CoreInfo.version)
    }
}
