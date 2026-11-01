package com.flappedear.betterdriver.io

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual typealias IOException = java.io.IOException

@Suppress("TooManyFunctions") // One small call per file operation the stores use.
actual object CoreFiles {
    actual fun join(dir: String, name: String): String = File(dir, name).path

    actual fun isFile(path: String): Boolean = File(path).isFile

    actual fun isDirectory(path: String): Boolean = File(path).isDirectory

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun size(path: String): Long = File(path).length()

    actual fun lastModifiedMillis(path: String): Long = File(path).lastModified()

    actual fun list(dir: String): List<String> = File(dir).list()?.toList().orEmpty()

    actual fun readText(path: String): String = File(path).readText()

    actual fun readBytes(path: String): ByteArray = File(path).readBytes()

    actual fun move(from: String, to: String): Boolean = File(from).renameTo(File(to))

    actual fun writeTextAtomically(path: String, text: String) =
        writeBytesAtomically(path, text.toByteArray(Charsets.UTF_8))

    actual fun writeBytesAtomically(path: String, bytes: ByteArray) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
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

    actual fun mkdirs(path: String): Boolean {
        val f = File(path)
        f.mkdirs()
        return f.isDirectory
    }
}
