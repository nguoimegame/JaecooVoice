package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    /** Every language JaecooApp can be set to. */
    private val appLanguages = setOf("vi", "en", "id", "th", "pt", "kk", "zh", "fr", "ar", "ru", "es")

    @Test
    fun everyAppLanguageHasAModel() {
        assertEquals(appLanguages, ModelCatalog.languages().toSet())
        appLanguages.forEach { assertTrue("no model for $it", ModelCatalog.forLanguage(it) != null) }
    }

    @Test
    fun lookupIgnoresUnknownLanguages() {
        assertEquals(ModelCatalog.VI_30M, ModelCatalog.forLanguage("vi"))
        assertEquals(ModelCatalog.EN_SMALL, ModelCatalog.forLanguage("en"))
        assertNull(ModelCatalog.forLanguage("de"))
    }

    @Test
    fun languagesWithoutAModelOfTheirOwnShareOneDownload() {
        val omnilingual = ModelCatalog.OMNILINGUAL
        listOf("ar", "pt", "th", "id", "kk").forEach { assertSame(omnilingual, ModelCatalog.forLanguage(it)) }
    }

    @Test
    fun modelIdsAreUnique() = assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)

    @Test
    fun everyModelListsFilesItsFamilyLoads() {
        val transducer = setOf(ModelCatalog.ENCODER, ModelCatalog.DECODER, ModelCatalog.JOINER, ModelCatalog.TOKENS)
        val single = setOf(ModelCatalog.MODEL, ModelCatalog.TOKENS)
        for (model in ModelCatalog.all) {
            val expected = if (model.family == ModelFamily.TRANSDUCER || model.family == ModelFamily.NEMO_TRANSDUCER) transducer else single
            assertEquals(model.id, expected, model.files)
            assertEquals(model.id, 64, model.sha256.length)
            assertTrue(model.id, model.url.endsWith("${model.archiveRoot}.tar.bz2") || model.url.endsWith(".tar.bz2"))
            // Archive names, when given, must land on exactly the files the engine loads.
            if (model.archiveNames.isNotEmpty()) assertEquals(model.id, model.files, model.archiveNames.values.toSet())
        }
    }

    @Test
    fun theVietnameseModelKeepsItsStoredIdentity() {
        assertEquals("zipformer-vi-30M-int8-2026-02-09", ModelCatalog.VI_30M.id)
        assertEquals(26_442_384L, ModelCatalog.VI_30M.sizeBytes)
    }
}
