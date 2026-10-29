package com.flappedear.betterdriver.io

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun newTestDir(): String {
    val dir = NSTemporaryDirectory() + "core-files-" + NSUUID().UUIDString
    PosixFiles.mkdirs(dir)
    return dir
}
