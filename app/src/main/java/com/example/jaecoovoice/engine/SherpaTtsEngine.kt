package com.example.jaecoovoice.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Result of audio synthesis containing normalized float PCM samples (-1..1) and sample rate.
 */
class TtsAudio(
    val samples: FloatArray,
    val sampleRate: Int
)

/**
 * Synthesizes text to speech waveforms offline.
 */
interface TtsEngine {
    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): TtsAudio
    fun generateStream(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        isCancelled: () -> Boolean = { false },
        onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
    ): Boolean
    fun release()
}

/**
 * Wraps sherpa-onnx [OfflineTts] using VITS/Piper models.
 * Runs on CPU with [numThreads] threads.
 */
class SherpaTtsEngine(
    model: TtsModel,
    modelDir: File,
    numThreads: Int = DEFAULT_THREADS,
    provider: String = DEFAULT_PROVIDER
) : TtsEngine {

    private val tts: OfflineTts

    init {
        val modelPath = File(modelDir, model.modelFile).absolutePath
        val tokensPath = File(modelDir, model.tokensFile).absolutePath
        val dataDirPath = model.dataDir?.let { File(modelDir, it).absolutePath }.orEmpty()
        val lexiconPath = model.lexiconFile?.let { File(modelDir, it).absolutePath }.orEmpty()
        val ruleFsts = model.ruleFsts?.split(",")?.joinToString(",") {
            File(modelDir, it.trim()).absolutePath
        }.orEmpty()

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelPath,
            tokens = tokensPath,
            dataDir = dataDirPath,
            lexicon = lexiconPath
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = numThreads,
            debug = false,
            provider = provider
        )
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = ruleFsts
        )
        tts = OfflineTts(null, config)
    }

    @Synchronized
    override fun generate(text: String, sid: Int, speed: Float): TtsAudio {
        val audio = tts.generate(text, sid, speed)
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    override fun generateStream(
        text: String,
        sid: Int,
        speed: Float,
        isCancelled: () -> Boolean,
        onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
    ): Boolean {
        if (isCancelled()) return false
        return try {
            var aborted = false
            val rate = tts.sampleRate()
            tts.generateWithCallback(text, sid, speed) { chunk ->
                if (isCancelled()) {
                    aborted = true
                    0
                } else {
                    onChunk(chunk, rate)
                    1
                }
            }
            !aborted && !isCancelled()
        } catch (e: Exception) {
            Log.e(TAG, "generateStream failed", e)
            false
        }
    }

    @Synchronized
    override fun release() {
        tts.release()
    }

    companion object {
        private const val TAG = "SherpaTtsEngine"
        const val DEFAULT_THREADS = 2
        const val DEFAULT_PROVIDER = "cpu"
    }
}

/**
 * Manages lazy-loading and lifecycle of [TtsEngine] instances for one active language.
 */
class TtsEngineSlot(
    private val factory: (TtsModel, File) -> TtsEngine = { model, dir -> SherpaTtsEngine(model, dir) }
) {
    private var engine: TtsEngine? = null
    private var language: String? = null

    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun get(language: String, model: TtsModel, dir: File): TtsEngine? {
        engine?.takeIf { this.language == language }?.let { return it }
        release()
        return try {
            factory(model, dir).also {
                engine = it
                this.language = language
            }
        } catch (e: LinkageError) {
            Log.e(TAG, "sherpa-onnx native library missing for TTS", e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot load TTS model for $language", e)
            null
        }
    }

    @Synchronized
    fun release() {
        engine?.release()
        engine = null
        language = null
    }

    private companion object {
        const val TAG = "TtsEngineSlot"
    }
}
