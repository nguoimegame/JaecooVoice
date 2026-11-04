package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsCatalogTest {

    private val expectedLanguages = listOf(
        "vi", "en", "ru", "es", "fr", "zh", "ar", "pt", "th", "id", "kk"
    )

    @Test
    fun allElevenLanguagesAreSupported() {
        assertEquals(expectedLanguages, TtsCatalog.languages())
    }

    @Test
    fun forLanguageReturnsModelForEachSupportedLanguage() {
        for (lang in expectedLanguages) {
            val model = TtsCatalog.forLanguage(lang)
            assertNotNull("Model should exist for $lang", model)
            assertEquals(lang, model?.language)
        }
    }

    @Test
    fun forLanguageReturnsNullForUnsupported() {
        assertNull(TtsCatalog.forLanguage("de"))
        assertNull(TtsCatalog.forLanguage("ja"))
        assertNull(TtsCatalog.forLanguage(""))
    }

    @Test
    fun allModelsHaveValidMetadata() {
        val models = TtsCatalog.all
        assertEquals(11, models.size)
        for (model in models) {
            assertTrue("id must not be blank", model.id.isNotBlank())
            assertTrue("label must not be blank", model.label.isNotBlank())
            assertTrue(
                "url must point to sherpa-onnx tts-models: ${model.url}",
                model.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/")
            )
            assertTrue("sizeBytes must be positive", model.sizeBytes > 0L)
            assertTrue("archiveRoot must not be blank", model.archiveRoot.isNotBlank())
            assertTrue("modelFile must be an onnx file", model.modelFile.endsWith(".onnx"))
            assertTrue("tokensFile must not be blank", model.tokensFile.isNotBlank())
            assertTrue(
                "sampleRate must be valid audio sample rate",
                model.sampleRate == 22050 || model.sampleRate == 16000
            )
        }
    }

    @Test
    fun piperModelsSpecifyDataDir() {
        val piperLanguages = listOf("vi", "en", "ru", "es", "fr", "ar", "pt", "id", "kk")
        for (lang in piperLanguages) {
            val model = TtsCatalog.forLanguage(lang)
            assertEquals("espeak-ng-data", model?.dataDir)
        }
    }

    @Test
    fun espeakVoices() {
        val expected = mapOf(
            "vi" to "vi",
            "en" to "en-us",
            "ru" to "ru",
            "es" to "es",
            "fr" to "fr-fr",
            "ar" to "ar",
            "pt" to "pt-br",
            "id" to "id",
            "kk" to "kk",
            "zh" to "",
            "th" to ""
        )
        assertEquals(expectedLanguages.toSet(), expected.keys)
        for ((lang, voice) in expected) {
            assertEquals("espeak voice of $lang", voice, TtsCatalog.forLanguage(lang)?.espeakVoice)
        }
    }

    @Test
    fun textFrontendModels() {
        for (model in TtsCatalog.all) {
            val text = model.language == "zh" || model.language == "th"
            assertEquals(model.language, if (text) TtsFrontend.TEXT else TtsFrontend.ESPEAK, model.frontend)
        }
    }

    @Test
    fun chineseModelSpecifiesLexicon() {
        val zh = TtsCatalog.forLanguage("zh")
        assertNotNull(zh)
        assertEquals("lexicon.txt", zh?.lexiconFile)
    }
}
