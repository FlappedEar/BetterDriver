package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.FileMp4Source
import java.io.File

/** The GPS samples of [file]; a damaged file throws [com.flappedear.betterdriver.video.mp4.Mp4FormatException]. */
fun GpmfGps.read(file: File): List<GpsSample> = FileMp4Source(file).use { read(it, file.name) }
