package com.flappedear.betterdriver.format

import java.util.Locale

actual fun fixed(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)
