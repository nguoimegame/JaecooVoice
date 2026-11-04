package com.example.jaecoovoice.engine

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Opens a download over HTTPS, following redirects (GitHub release assets redirect to a CDN). */
internal object HttpSource {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val HTTP_OK = 200
    private const val HTTP_LAST_OK = 299

    /** A response body that knows its Content-Length and closes its connection. */
    class SizedStream(stream: InputStream, val length: Long, private val connection: HttpURLConnection) : FilterInputStream(stream) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    fun open(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        if (code !in HTTP_OK..HTTP_LAST_OK) {
            connection.disconnect()
            throw IOException("HTTP $code")
        }
        return SizedStream(connection.inputStream, connection.contentLengthLong, connection)
    }
}
