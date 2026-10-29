package com.flappedear.betterdriver.io

import java.nio.file.Files

actual fun newTestDir(): String = Files.createTempDirectory("core-files").toString()
