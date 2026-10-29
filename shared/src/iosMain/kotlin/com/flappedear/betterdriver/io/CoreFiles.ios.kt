package com.flappedear.betterdriver.io

actual open class IOException actual constructor(message: String?) : Exception(message)

actual object CoreFiles {
    actual fun join(dir: String, name: String): String = PosixFiles.join(dir, name)

    actual fun isFile(path: String): Boolean = PosixFiles.isFile(path)

    actual fun isDirectory(path: String): Boolean = PosixFiles.isDirectory(path)

    actual fun exists(path: String): Boolean = isFile(path) || isDirectory(path)

    actual fun size(path: String): Long = PosixFiles.size(path)

    actual fun list(dir: String): List<String> = PosixFiles.list(dir)

    actual fun readText(path: String): String = PosixFiles.readBytes(path).decodeToString()

    actual fun writeTextAtomically(path: String, text: String) {
        val parent = PosixFiles.parent(path)
        if (!PosixFiles.mkdirs(parent)) throw FileError("mkdirs $parent")
        PosixFiles.writeAtomically(path, text.encodeToByteArray())
    }

    actual fun delete(path: String): Boolean = isFile(path) && platform.posix.unlink(path) == 0

    actual fun deleteRecursively(path: String): Boolean {
        if (exists(path)) PosixFiles.deleteRecursively(path)
        return !exists(path)
    }
}
