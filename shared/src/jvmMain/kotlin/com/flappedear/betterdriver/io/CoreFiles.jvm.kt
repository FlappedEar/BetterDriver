package com.flappedear.betterdriver.io

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual typealias IOException = java.io.IOException

actual object CoreFiles {
    actual fun join(dir: String, name: String): String = File(dir, name).path

    actual fun isFile(path: String): Boolean = File(path).isFile

    actual fun isDirectory(path: String): Boolean = File(path).isDirectory

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun size(path: String): Long = File(path).length()

    actual fun list(dir: String): List<String> = File(dir).list()?.toList().orEmpty()

    actual fun readText(path: String): String = File(path).readText()

    actual fun writeTextAtomically(path: String, text: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    actual fun delete(path: String): Boolean = File(path).delete()

    actual fun deleteRecursively(path: String): Boolean {
        val f = File(path)
        f.deleteRecursively()
        return !f.exists()
    }
}
