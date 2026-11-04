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
    private val slot: TtsEngineSlot = TtsEngineSlot { model, dir ->
        SherpaTtsEngine(model, dir, EspeakPhonemizer(EspeakData.dir(filesDir)))
    },
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

    /** How one [synthesize] call ended. */
    sealed interface SynthResult {
        data object Spoken : SynthResult
        data object Cancelled : SynthResult

        /** [reason] is logged by JaecooApp (docs/jaecoovoice-contract.md lists them). */
        data class Failed(val reason: String) : SynthResult
    }

    /**
     * Synthesizes [text] in [language] streaming chunks of PCM audio to [onChunk].
     * Aborts promptly when [isCancelled] returns true. Producing no audio at all is a failure.
     */
    @Synchronized
    @Suppress("ReturnCount") // one early return for each way it cannot even start
    fun synthesize(
        language: String,
        text: String,
        isCancelled: () -> Boolean = { false },
        onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
    ): SynthResult {
        val model = TtsCatalog.forLanguage(language)
        if (model == null || !TtsModelStore.isInstalled(filesDir, model)) {
            return SynthResult.Failed("tts model not installed: $language")
        }
        if (text.isBlank()) return SynthResult.Spoken
        val engine = slot.get(language, model, TtsModelStore.dir(filesDir, model))
            ?: return SynthResult.Failed("tts model failed to load: $language")
        lastUsedAt = clock()
        val samples = engine.generateStream(text, isCancelled = isCancelled, onChunk = onChunk)
        lastUsedAt = clock()
        return when {
            isCancelled() -> SynthResult.Cancelled
            samples == TtsEngine.PHONEMIZER_UNAVAILABLE -> SynthResult.Failed("espeak phonemizer unavailable: ${model.espeakVoice}")
            samples == TtsEngine.SYNTHESIS_FAILED -> SynthResult.Failed("tts synthesis failed: $language")
            samples <= 0 -> SynthResult.Failed("tts produced no audio: $language")
            else -> SynthResult.Spoken
        }
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
