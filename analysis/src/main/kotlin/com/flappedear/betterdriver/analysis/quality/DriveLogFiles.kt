package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.LogLine
import java.io.File
import java.util.zip.ZipFile

/**
 * The reports of every recording in a shared log: a zip as Share log makes it, a single log file, or
 * a folder of log files. Older builds' per-process files are named after their process.
 */
fun driveChecks(file: File): List<QualityReport> = driveChecks(readLog(file))

/** The log lines of [file]: a zip, a folder or one file; see [driveChecks]. */
fun readLog(file: File): List<LogLine> = when {
    file.isDirectory -> file.listFiles().orEmpty().filter { it.name.endsWith(".log") }
        .flatMap { readLog(it) }
    file.name.endsWith(".zip") -> ZipFile(file).use { zip ->
        zip.entries().toList().filter { it.name.endsWith(".log") }.flatMap { entry ->
            zip.getInputStream(entry).bufferedReader().useLines { DriveLog.parse(it, processOf(entry.name)) }
        }
    }
    else -> file.bufferedReader().useLines { DriveLog.parse(it, processOf(file.name)) }
}

/** "recorder.1.log" was written by the recorder process. */
private fun processOf(name: String) = name.substringAfterLast('/').substringBefore('.')
