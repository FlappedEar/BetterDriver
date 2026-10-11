package com.flappedear.betterdriver.updater

import java.io.IOException
import java.io.OutputStream

/** An HTTP answer. Header names are lower case. */
class Response(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0)) {
    val ok: Boolean get() = code in 200..299
}

/** HTTPS access for the updater; the app uses HttpURLConnection, tests a fake. */
interface Network {
    /**
     * GET [url] with [headers]. The body is read only for 2xx answers and is at most [maxBytes].
     * @throws IOException when offline or the connection fails.
     */
    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = MaxSmallBody): Response

    /**
     * GET [url] and copy a 2xx body to [out], following redirects. Stops with an IOException after
     * [maxBytes], or when [keepGoing] (called with the bytes so far) returns false. A non-2xx answer
     * is returned with nothing written.
     */
    fun download(url: String, out: OutputStream, maxBytes: Long, keepGoing: (Long) -> Boolean): Response

    companion object {
        const val MaxSmallBody = 1 shl 20
    }
}
