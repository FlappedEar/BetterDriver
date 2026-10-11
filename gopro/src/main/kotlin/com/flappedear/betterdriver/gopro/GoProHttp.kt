package com.flappedear.betterdriver.gopro

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * The camera's HTTP API over its own Wi-Fi access point: http://10.5.5.9:8080
 * (https://gopro.github.io/OpenGoPro/tutorials/camera-media-list). [open] lets Android route the
 * connection over the camera's network (Network.openConnection) instead of the default one,
 * which has no internet and would otherwise not be used.
 */
class GoProHttp(
    private val baseUrl: String = "http://10.5.5.9:8080",
    private val open: (URL) -> URLConnection = { it.openConnection() },
    private val timeoutMillis: Int = 10_000,
) {
    fun mediaList(): List<MediaFile> =
        GoProMedia.parseList(get("/gopro/media/list").use { it.readBytes() }.decodeToString())

    /** Keeps the camera awake while it serves files (HTTP keep-alive endpoint). */
    fun keepAlive() {
        get("/gopro/camera/keep_alive").use { it.readBytes() }
    }

    /**
     * Downloads [file] to [target] through a `.part` file that is renamed when complete, so a
     * cut-off download never looks finished. [onProgress] gets bytes so far and the total.
     */
    fun download(file: MediaFile, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val part = File(target.parentFile, target.name + ".part")
        val total = get("/videos/DCIM/${file.path}").use { input ->
            part.outputStream().use { out -> copy(input, out) { onProgress(it, file.size) }.also { out.fd.sync() } }
        }
        if (file.size > 0 && total != file.size) {
            part.delete()
            throw IOException("${file.name}: got $total of ${file.size} bytes")
        }
        if (!part.renameTo(target)) throw IOException("could not rename ${part.name}")
    }

    private fun copy(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit): Long {
        val buffer = ByteArray(BufferBytes)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            onProgress(total)
        }
        return total
    }

    private fun get(path: String) = (open(URL(baseUrl + path)) as HttpURLConnection).run {
        connectTimeout = timeoutMillis
        readTimeout = timeoutMillis
        if (responseCode != HttpURLConnection.HTTP_OK) {
            disconnect()
            throw IOException("GET $path: HTTP $responseCode")
        }
        inputStream
    }

    private companion object {
        const val BufferBytes = 256 * 1024
    }
}
