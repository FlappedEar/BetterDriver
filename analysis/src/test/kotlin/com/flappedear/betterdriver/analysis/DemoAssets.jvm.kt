package com.flappedear.betterdriver.analysis

import java.io.File

actual fun demoAsset(name: String): String = File(DemoAssetsDir, name).readText()
