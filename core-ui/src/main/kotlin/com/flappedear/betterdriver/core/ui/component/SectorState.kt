package com.flappedear.betterdriver.core.ui.component

import androidx.compose.runtime.Immutable

/** One sector on the strip. [deltaSeconds] is null while the sector is not finished. */
@Immutable
data class SectorState(val name: String, val deltaSeconds: Double?, val current: Boolean = false)
