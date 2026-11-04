package com.example.jaecoovoice.engine

/** Keys of the Bundle `IVoiceEngine.modelStatus` returns (docs/jaecoovoice-contract.md). */
object StatusKeys {
    const val LANGUAGE = "language"
    const val SUPPORTED = "supported"
    const val INSTALLED = "installed"
    const val DOWNLOADING = "downloading"
    const val LABEL = "label"
    const val SIZE_BYTES = "sizeBytes"
    const val LOAD_ERROR = "loadError"

    /** The status of [language]; [model] is null when the catalog has none for it. */
    fun status(language: String, model: VoiceModel?, installed: Boolean, downloading: Boolean, loadError: String?): Map<String, Any> = buildMap {
        put(LANGUAGE, language)
        put(SUPPORTED, model != null)
        put(INSTALLED, installed)
        put(DOWNLOADING, downloading)
        if (model != null) {
            put(LABEL, model.label)
            put(SIZE_BYTES, model.sizeBytes)
        }
        if (loadError != null) put(LOAD_ERROR, loadError)
    }
}
