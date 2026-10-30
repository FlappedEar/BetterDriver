package com.flappedear.betterdriver.timing.track

import java.io.File

/** The tracks in folder [dir] on Android, where folders are java.io.File. */
fun TrackStore(dir: File): TrackStore = TrackStore(dir.path)
