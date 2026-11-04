package com.example.jaecoovoice.engine

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the loaded engine for one language at a time and remembers why a load failed. Only
 * [loadError] is thread-safe: [EngineCore] uses the rest from its single engine thread.
 */
internal class EngineSlot(private val factory: (VoiceModel, File) -> SpeechEngine) {

    private var engine: SpeechEngine? = null
    private var language: String? = null
    private val loadErrors = ConcurrentHashMap<String, String>()

    fun loadError(language: String): String? = loadErrors[language]

    /** The engine for [language], loading [model] from [dir] if needed; null when that fails. */
    @Suppress("TooGenericExceptionCaught")
    fun get(language: String, model: VoiceModel, dir: File): SpeechEngine? {
        engine?.takeIf { this.language == language }?.let { return it }
        release()
        return try {
            factory(model, dir).also {
                engine = it
                this.language = language
                loadErrors.remove(language)
            }
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError (no .so for this ABI) and NoClassDefFoundError.
            Log.e(TAG, "sherpa-onnx native library missing", e)
            loadErrors[language] = describe(e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot load speech model", e)
            loadErrors[language] = describe(e)
            null
        }
    }

    fun release() {
        engine?.release()
        engine = null
        language = null
    }

    private companion object {
        const val TAG = "EngineSlot"
    }
}
