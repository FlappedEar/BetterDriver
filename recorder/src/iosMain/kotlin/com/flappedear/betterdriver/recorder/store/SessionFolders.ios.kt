package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.PosixFiles

internal actual fun sessionLocked(dir: String): Boolean =
    PosixFiles.isLocked(PosixFiles.join(dir, SessionFileNames.LOCK))

internal actual fun syncFolder(dir: String) = PosixFiles.syncDir(dir)
