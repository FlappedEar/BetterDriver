package com.flappedear.betterdriver.analysis

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun newTestDir(name: String): String {
    val dir = NSTemporaryDirectory() + name + "-" + NSUUID().UUIDString
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    return dir
}
