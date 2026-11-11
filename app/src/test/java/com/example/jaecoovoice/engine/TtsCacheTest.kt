package com.example.jaecoovoice.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000L

    private fun cache(maxBytes: Long = TtsCache.DEFAULT_MAX_BYTES) = TtsCache(File(temp.root, "tts"), maxBytes) { now++ }

    private fun speech(size: Int) = TtsCache.Speech(ShortArray(size) { it.toShort() }, 22050)

    @Test
    fun putThenGetRoundTrips() {
        val cache = cache()
        cache.put("k", speech(5))
        val back = cache.get("k")!!
        assertEquals(22050, back.sampleRate)
        assertArrayEquals(shortArrayOf(0, 1, 2, 3, 4), back.samples)
    }

    @Test
    fun missingKeyIsAMiss() = assertNull(cache().get("nope"))

    @Test
    fun keyDiffersByVersionModelAndText() {
        val cache = cache()
        val base = cache.key(1, "vi-medium", "xin chào")
        assertNotEquals(base, cache.key(2, "vi-medium", "xin chào"))
        assertNotEquals(base, cache.key(1, "en-medium", "xin chào"))
        assertNotEquals(base, cache.key(1, "vi-medium", "tạm biệt"))
        assertEquals(base, cache.key(1, "vi-medium", "xin chào"))
    }

    @Test
    fun evictsTheLeastRecentlyUsedOverTheCap() {
        // Each entry is 4 + 2 * 100 = 204 bytes; room for two.
        val cache = cache(maxBytes = 450)
        cache.put("a", speech(100))
        cache.put("b", speech(100))
        assertNotNull(cache.get("a"))
        cache.put("c", speech(100))
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun aTruncatedFileIsDeletedAndMisses() {
        val cache = cache()
        cache.put("k", speech(10))
        val file = File(temp.root, "tts/k.pcm")
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
        assertNull(cache.get("k"))
        assertFalse(file.exists())
    }

    @Test
    fun anEmptyFileIsDeletedAndMisses() {
        val cache = cache()
        cache.put("k", speech(1))
        File(temp.root, "tts/k.pcm").writeBytes(ByteArray(0))
        assertNull(cache.get("k"))
    }

    @Test
    fun overlongOrBlankTextIsNotCacheable() {
        val cache = cache()
        assertTrue(cache.isCacheable("a".repeat(200)))
        assertFalse(cache.isCacheable("a".repeat(201)))
        assertFalse(cache.isCacheable("  "))
    }

    @Test
    fun clearRemovesEverything() {
        val cache = cache()
        cache.put("a", speech(3))
        cache.clear()
        assertNull(cache.get("a"))
    }

    @Test
    fun aFileWithAnImpossibleSampleRateIsDeletedAndMisses() {
        val cache = cache()
        cache.put("k", TtsCache.Speech(ShortArray(4), 123_456_789))
        assertNull(cache.get("k"))
        assertFalse(File(temp.root, "tts/k.pcm").exists())
    }
}
