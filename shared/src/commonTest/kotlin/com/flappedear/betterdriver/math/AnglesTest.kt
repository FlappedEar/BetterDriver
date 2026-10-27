package com.flappedear.betterdriver.math

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class AnglesTest {
    @Test
    fun convertsLikeTheJdk() {
        assertEquals(PI, toRadians(180.0))
        assertEquals(0.017453292519943295, toRadians(1.0))
        assertEquals(57.29577951308232, toDegrees(1.0))
        assertEquals(-90.0, toDegrees(-PI / 2))
    }
}
