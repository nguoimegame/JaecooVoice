package com.example.jaecoovoice.engine

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Spoken replies kept on disk as 16-bit PCM, one file per sentence, so a reply heard before plays
 * without the model. Least recently used files go first once [maxBytes] is exceeded. Nothing here
 * throws: a file that cannot be read is deleted and counts as a miss.
 *
 * File layout: sample rate (4 bytes, big endian), then the samples (2 bytes each, big endian).
 */
class TtsCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    class Speech(val samples: ShortArray, val sampleRate: Int)

    fun isCacheable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH

    /** Changes with the app version and the model, so an old voice is never replayed. */
    fun key(versionCode: Long, modelId: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$versionCode\n$modelId\n$text".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(key: String): Speech? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val speech = try {
            decode(file.readBytes())
        } catch (e: IOException) {
            Log.w(TAG, "cannot read cached speech", e)
            null
        }
        if (speech == null) {
            file.delete()
        } else {
            file.setLastModified(clock())
        }
        return speech
    }

    @Synchronized
    fun put(key: String, speech: Speech) {
        try {
            dir.mkdirs()
            val temp = File(dir, "$key$TEMP_SUFFIX")
            temp.writeBytes(encode(speech))
            val file = fileFor(key)
            if (temp.renameTo(file)) file.setLastModified(clock()) else temp.delete()
            evict()
        } catch (e: IOException) {
            // A cache that cannot write is just a cache that misses.
            Log.w(TAG, "cannot cache speech", e)
        }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String) = File(dir, "$key$SUFFIX")

    private fun evict() {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun encode(speech: Speech): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + speech.samples.size * 2)
        buffer.putInt(speech.sampleRate)
        speech.samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): Speech? {
        val body = bytes.size - HEADER_BYTES
        val buffer = ByteBuffer.wrap(bytes)
        val rate = if (body >= 2) buffer.getInt() else 0
        return if (rate in MIN_RATE..MAX_RATE && body % 2 == 0) Speech(ShortArray(body / 2) { buffer.getShort() }, rate) else null
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val MAX_TEXT_LENGTH = 200
        private const val TAG = "TtsCache"
        private const val HEADER_BYTES = 4

        /** A header outside this range is damage; AudioTrack would refuse it every time. */
        private const val MIN_RATE = 8_000
        private const val MAX_RATE = 48_000
        private const val SUFFIX = ".pcm"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
