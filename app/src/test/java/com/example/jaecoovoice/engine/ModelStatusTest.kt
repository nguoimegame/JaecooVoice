package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStatusTest {

    @Test
    fun unsupportedLanguageStatus() = assertEquals(
        mapOf("language" to "en", "supported" to false, "installed" to false, "downloading" to false),
        StatusKeys.status("en", null, installed = false, downloading = false, loadError = null)
    )

    @Test
    fun installedStatusCarriesLabelSizeAndLoadError() = assertEquals(
        mapOf(
            "language" to "vi",
            "supported" to true,
            "installed" to true,
            "downloading" to false,
            "label" to "Zipformer tiếng Việt 30M",
            "sizeBytes" to 26_442_384L,
            "loadError" to "RuntimeException: x"
        ),
        StatusKeys.status("vi", ModelCatalog.VI_30M, installed = true, downloading = false, loadError = "RuntimeException: x")
    )

    @Test
    fun noLoadErrorMeansNoKey() = assertEquals(
        false,
        StatusKeys.status("vi", ModelCatalog.VI_30M, installed = false, downloading = true, loadError = null).containsKey("loadError")
    )
}
