package com.flappedear.betterdriver.analysis

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
actual fun demoAsset(name: String): String =
    checkNotNull(NSString.stringWithContentsOfFile("$DemoAssetsDir/$name", NSUTF8StringEncoding, null)) {
        "cannot read $DemoAssetsDir/$name"
    }
