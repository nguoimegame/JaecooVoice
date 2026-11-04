package com.example.jaecoovoice

import android.speech.tts.TextToSpeech
import com.example.jaecoovoice.engine.TtsCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JaecooTtsServiceTest {

    @Test
    fun floatTo16BitPcmConvertsZero() {
        val input = floatArrayOf(0.0f)
        val expected = byteArrayOf(0x00.toByte(), 0x00.toByte())
        assertArrayEquals(expected, PcmAudioConverter.floatTo16BitPcm(input))
    }

    @Test
    fun floatTo16BitPcmConvertsFullRangePositive() {
        val input = floatArrayOf(1.0f)
        // 1.0f * 32767.0f = 32767 = 0x7FFF -> little endian: 0xFF, 0x7F
        val expected = byteArrayOf(0xFF.toByte(), 0x7F.toByte())
        assertArrayEquals(expected, PcmAudioConverter.floatTo16BitPcm(input))
    }

    @Test
    fun floatTo16BitPcmConvertsFullRangeNegative() {
        val input = floatArrayOf(-1.0f)
        // -1.0f * 32767.0f = -32767 = 0x8001 -> little endian: 0x01, 0x80
        val expected = byteArrayOf(0x01.toByte(), 0x80.toByte())
        assertArrayEquals(expected, PcmAudioConverter.floatTo16BitPcm(input))
    }

    @Test
    fun floatTo16BitPcmClampsValuesBeyondRange() {
        val input = floatArrayOf(1.5f, -2.0f)
        val expected = byteArrayOf(
            0xFF.toByte(),
            0x7F.toByte(),
            0x01.toByte(),
            0x80.toByte()
        )
        assertArrayEquals(expected, PcmAudioConverter.floatTo16BitPcm(input))
    }

    @Test
    fun floatTo16BitPcmPreservesLittleEndianByteOrder() {
        // 0.5f * 32767.0f = 16383 = 0x3FFF -> little endian: 0xFF, 0x3F
        val input = floatArrayOf(0.5f)
        val bytes = PcmAudioConverter.floatTo16BitPcm(input)
        assertEquals(2, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x3F.toByte(), bytes[1])

        val shortVal = ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        assertEquals(16383.toShort(), shortVal)
    }

    @Test
    fun floatTo16BitPcmHandlesEmptyArray() {
        val input = floatArrayOf()
        val expected = byteArrayOf()
        assertArrayEquals(expected, PcmAudioConverter.floatTo16BitPcm(input))
    }

    @Test
    fun normalizeLanguageHandlesIso6391Codes() {
        assertEquals("vi", PcmAudioConverter.normalizeLanguage("vi"))
        assertEquals("en", PcmAudioConverter.normalizeLanguage("en"))
        assertEquals("ru", PcmAudioConverter.normalizeLanguage("ru"))
        assertEquals("es", PcmAudioConverter.normalizeLanguage("es"))
        assertEquals("fr", PcmAudioConverter.normalizeLanguage("fr"))
        assertEquals("zh", PcmAudioConverter.normalizeLanguage("zh"))
        assertEquals("ar", PcmAudioConverter.normalizeLanguage("ar"))
        assertEquals("pt", PcmAudioConverter.normalizeLanguage("pt"))
        assertEquals("th", PcmAudioConverter.normalizeLanguage("th"))
        assertEquals("id", PcmAudioConverter.normalizeLanguage("id"))
        assertEquals("kk", PcmAudioConverter.normalizeLanguage("kk"))
    }

    @Test
    fun normalizeLanguageHandlesIso6392Codes() {
        assertEquals("vi", PcmAudioConverter.normalizeLanguage("vie"))
        assertEquals("en", PcmAudioConverter.normalizeLanguage("eng"))
        assertEquals("ru", PcmAudioConverter.normalizeLanguage("rus"))
        assertEquals("es", PcmAudioConverter.normalizeLanguage("spa"))
        assertEquals("fr", PcmAudioConverter.normalizeLanguage("fra"))
        assertEquals("fr", PcmAudioConverter.normalizeLanguage("fre"))
        assertEquals("zh", PcmAudioConverter.normalizeLanguage("zho"))
        assertEquals("zh", PcmAudioConverter.normalizeLanguage("chi"))
        assertEquals("ar", PcmAudioConverter.normalizeLanguage("ara"))
        assertEquals("pt", PcmAudioConverter.normalizeLanguage("por"))
        assertEquals("th", PcmAudioConverter.normalizeLanguage("tha"))
        assertEquals("id", PcmAudioConverter.normalizeLanguage("ind"))
        assertEquals("kk", PcmAudioConverter.normalizeLanguage("kaz"))
    }

    @Test
    fun normalizeLanguageHandlesVariantsAndCase() {
        assertEquals("vi", PcmAudioConverter.normalizeLanguage("vi-VN"))
        assertEquals("vi", PcmAudioConverter.normalizeLanguage("VI_VN"))
        assertEquals("vi", PcmAudioConverter.normalizeLanguage("VIE-VNM"))
        assertEquals("en", PcmAudioConverter.normalizeLanguage("en_US"))
        assertEquals("en", PcmAudioConverter.normalizeLanguage("ENG"))
        assertEquals("id", PcmAudioConverter.normalizeLanguage("in"))
    }

    @Test
    fun normalizeLanguageReturnsNullForUnsupportedOrEmpty() {
        assertNull(PcmAudioConverter.normalizeLanguage(null))
        assertNull(PcmAudioConverter.normalizeLanguage(""))
        assertNull(PcmAudioConverter.normalizeLanguage("   "))
        assertNull(PcmAudioConverter.normalizeLanguage("ja"))
        assertNull(PcmAudioConverter.normalizeLanguage("jpn"))
        assertNull(PcmAudioConverter.normalizeLanguage("de"))
        assertNull(PcmAudioConverter.normalizeLanguage("xyz"))
    }

    @Test
    fun checkLanguageAvailableReturnsCorrectStatus() {
        val installedLangs = setOf("vi", "en")
        val isInstalled = { lang: String -> lang in installedLangs }

        // Supported and installed
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            PcmAudioConverter.checkLanguageAvailable("vi", isInstalled)
        )
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            PcmAudioConverter.checkLanguageAvailable("vie", isInstalled)
        )
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            PcmAudioConverter.checkLanguageAvailable("en_US", isInstalled)
        )

        // Supported but not installed
        assertEquals(
            TextToSpeech.LANG_MISSING_DATA,
            PcmAudioConverter.checkLanguageAvailable("ru", isInstalled)
        )
        assertEquals(
            TextToSpeech.LANG_MISSING_DATA,
            PcmAudioConverter.checkLanguageAvailable("rus", isInstalled)
        )

        // Unsupported
        assertEquals(
            TextToSpeech.LANG_NOT_SUPPORTED,
            PcmAudioConverter.checkLanguageAvailable("ja", isInstalled)
        )
        assertEquals(
            TextToSpeech.LANG_NOT_SUPPORTED,
            PcmAudioConverter.checkLanguageAvailable(null, isInstalled)
        )
    }

    @Test
    fun languageInfoCoversAllTtsCatalogModels() {
        for (model in TtsCatalog.all) {
            val info = PcmAudioConverter.LANGUAGE_INFO[model.language]
            assertNotNull("Missing LANGUAGE_INFO for ${model.language}", info)
            assertEquals(3, info!!.iso3Language.length)
            assertEquals(3, info.iso3Country.length)
            assertEquals(model.language, info.locale.language)
        }
    }
}
