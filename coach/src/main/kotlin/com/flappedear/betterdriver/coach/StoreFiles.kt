package com.flappedear.betterdriver.coach

import java.io.File

/** [writeAtomically] for the stores still on java.io.File, until they move to the shared core (FBBD-359). */
internal fun writeAtomically(target: File, text: String) = writeAtomically(target.path, text)
