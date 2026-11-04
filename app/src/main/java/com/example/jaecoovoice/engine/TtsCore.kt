package com.example.jaecoovoice.engine

import android.content.Context
import java.io.File

/**
 * Shared offline TTS synthesis core managing engine lifecycle and streaming.
 * Both VoiceEngineService and JaecooTtsService access this core so only one model
 * is loaded in memory at a time.
 */
class TtsCore(
    private val filesDir: File,
    private val slot: TtsEngineSlot = TtsEngineSlot(),
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private var lastUsedAt: Long = 0L

    /**
     * Checks if the offline model for [language] is downloaded and complete.
     */
    fun isInstalled(language: String): Boolean {
        val model = TtsCatalog.forLanguage(language) ?: return false
        return TtsModelStore.isInstalled(filesDir, model)
    }

    /**
     * Synthesizes [text] in [language] streaming chunks of PCM audio to [onChunk].
     * Aborts promptly when [isCancelled] returns true.
     */
    @Synchronized
    fun synthesize(
        language: String,
        text: String,
        isCancelled: () -> Boolean = { false },
        onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
    ): Boolean {
        val model = TtsCatalog.forLanguage(language)
        val installed = model != null && TtsModelStore.isInstalled(filesDir, model)
        val engine = if (installed) slot.get(language, model!!, TtsModelStore.dir(filesDir, model)) else null
        if (engine == null) {
            return false
        }
        lastUsedAt = clock()
        val result = engine.generateStream(text, isCancelled = isCancelled, onChunk = onChunk)
        lastUsedAt = clock()
        return result
    }

    /**
     * Releases native TTS resources if no synthesis has occurred within [idleTimeoutMs].
     */
    @Synchronized
    fun releaseIfIdle() {
        if (clock() - lastUsedAt >= idleTimeoutMs) {
            slot.release()
        }
    }

    /**
     * Unconditionally releases native TTS resources.
     */
    @Synchronized
    fun release() {
        slot.release()
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: TtsCore? = null

        fun get(context: Context): TtsCore {
            return instance ?: synchronized(this) {
                instance ?: TtsCore(context.applicationContext.filesDir).also { instance = it }
            }
        }

        fun resetForTesting(testInstance: TtsCore? = null) {
            synchronized(this) {
                instance = testInstance
            }
        }
    }
}
