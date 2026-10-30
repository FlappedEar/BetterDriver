package com.flappedear.betterdriver.recorder.store

import java.io.File

internal actual fun sessionLocked(dir: String): Boolean = SessionFiles.isLocked(File(dir))

internal actual fun syncFolder(dir: String) = SessionFiles.syncDir(File(dir))
