package com.example.jaecoovoice.engine

/** Error codes of `IListenCallback.onError` (docs/jaecoovoice-contract.md). Never renumber. */
object VoiceErrors {
    const val NO_SPEECH = 1
    const val CANCELLED = 2
    const val MODEL_MISSING = 3
    const val MODEL_LOAD_FAILED = 4
    const val NO_MIC_PERMISSION = 5
    const val MIC_UNAVAILABLE = 6
    const val MIC_SILENT = 7
    const val RECOGNIZE_FAILED = 8
    const val BUSY = 9
    const val LANGUAGE_UNSUPPORTED = 10
}
