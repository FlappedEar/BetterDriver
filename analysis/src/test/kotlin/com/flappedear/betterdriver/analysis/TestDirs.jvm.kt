package com.flappedear.betterdriver.analysis

import java.nio.file.Files

actual fun newTestDir(name: String): String = Files.createTempDirectory(name).toFile().apply { deleteOnExit() }.path
