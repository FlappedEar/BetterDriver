package com.flappedear.betterdriver.update

import com.flappedear.betterdriver.updater.Network
import com.flappedear.betterdriver.updater.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The updater's network over HTTPS only. Redirects are followed (release assets redirect to a CDN). */
internal class HttpNetwork(private val userAgent: String) : Network {

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response = open(url, headers) { c ->
        val body = if (c.responseCode in OkCodes) {
            ByteArrayOutputStream().also { out -> c.inputStream.use { copy(it, out, maxBytes.toLong()) { true } } }
                .toByteArray()
        } else {
            ByteArray(0)
        }
        Response(c.responseCode, headersOf(c), body)
    }

    override fun download(url: String, out: OutputStream, maxBytes: Long, keepGoing: (Long) -> Boolean): Response =
        open(url, emptyMap()) { c ->
            if (c.responseCode in OkCodes) c.inputStream.use { copy(it, out, maxBytes, keepGoing) }
            Response(c.responseCode, headersOf(c))
        }

    private fun <T> open(url: String, headers: Map<String, String>, use: (HttpsURLConnection) -> T): T {
        if (!url.startsWith("https://")) throw IOException("not HTTPS: $url")
        val c = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("not HTTPS: $url")
        try {
            c.connectTimeout = TimeoutMillis
            c.readTimeout = TimeoutMillis
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val result = use(c)
            // A redirect to plain HTTP would not be followed, but make sure nothing ended up off HTTPS.
            if (c.url.protocol != "https") throw IOException("redirected off HTTPS")
            return result
        } finally {
            c.disconnect()
        }
    }

    private fun headersOf(c: HttpsURLConnection): Map<String, String> = c.headerFields
        .filterKeys { it != null }
        .mapNotNull { (k, v) -> v.firstOrNull()?.let { k.lowercase() to it } }
        .toMap()

    private fun copy(input: InputStream, out: OutputStream, maxBytes: Long, keepGoing: (Long) -> Boolean) {
        val buffer = ByteArray(BufferBytes)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            if (total > maxBytes) throw IOException("larger than expected")
            out.write(buffer, 0, n)
            if (!keepGoing(total)) throw IOException("stopped")
        }
    }

    private companion object {
        const val TimeoutMillis = 30_000
        const val BufferBytes = 64 * 1024
        val OkCodes = 200..299
    }
}
