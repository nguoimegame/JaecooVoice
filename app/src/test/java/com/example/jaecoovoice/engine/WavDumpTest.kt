package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class WavDumpTest {

    @Test
    fun headerDescribesMono16kPcm() {
        val bytes = WavDump.wav(floatArrayOf(0f, 1f, -1f), 16_000)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 6, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(bytes.size - 8, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(6, b.getInt(40))
        assertEquals(0, b.getShort(44).toInt())
        assertEquals(Short.MAX_VALUE.toInt(), b.getShort(46).toInt())
        assertEquals(-Short.MAX_VALUE, b.getShort(48).toInt())
    }

    @Test
    fun keepsOnlyTheNewestFiftyPairs() {
        val dir = createTempDirectory().toFile()
        for (i in 1..WavDump.KEEP + 3) WavDump.write(dir, floatArrayOf(0f), "câu $i", 1_000L + i)
        val wavs = dir.listFiles { f -> f.extension == "wav" }.orEmpty().map { it.nameWithoutExtension }.sorted()
        assertEquals(WavDump.KEEP, wavs.size)
        assertEquals("1004", wavs.first())
        assertEquals("câu 53", dir.resolve("1053.txt").readText())
        assertEquals(WavDump.KEEP, dir.listFiles { f -> f.extension == "txt" }.orEmpty().size)
        dir.deleteRecursively()
    }
}
