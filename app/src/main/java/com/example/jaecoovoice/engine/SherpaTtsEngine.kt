package com.example.jaecoovoice.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
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

    /**
     * Streams [text]'s audio to [onChunk]. Returns the number of samples streamed (0 when the
     * model produced nothing), [PHONEMIZER_UNAVAILABLE] or [SYNTHESIS_FAILED].
     */
    fun generateStream(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        isCancelled: () -> Boolean = { false },
        onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
    ): Int
    fun release()

    /** Starts what the first [generateStream] would otherwise start (espeak-ng); nothing by default. */
    fun prime() {
        // Default no-op.
    }

    companion object {
        /** espeak-ng could not start, or does not know the model's voice. */
        const val PHONEMIZER_UNAVAILABLE = -1

        /** sherpa-onnx threw. */
        const val SYNTHESIS_FAILED = -2
    }
}

/**
 * Wraps sherpa-onnx [OfflineTts] using VITS/Piper models.
 * Runs on CPU with [numThreads] threads.
 *
 * sherpa-onnx 2.x has no phonemizer, so Piper models ([TtsFrontend.ESPEAK]) are phonemized here
 * with [phonemizer] and passed as `GenerationConfig.phonemeCodepoints`; other models
 * ([TtsFrontend.TEXT]) still pass the text.
 */
class SherpaTtsEngine(
    private val model: TtsModel,
    modelDir: File,
    private val phonemizer: EspeakPhonemizer?,
    numThreads: Int = DEFAULT_THREADS,
    provider: String = DEFAULT_PROVIDER,
    /** Told before each native step, so a native crash can be placed ([CrashTrace]). */
    private val onStep: (String) -> Unit = {}
) : TtsEngine {

    private val tts: OfflineTts

    override fun prime() {
        if (model.frontend == TtsFrontend.ESPEAK) phonemizer?.clauses(PRIME_TEXT, model.espeakVoice)
    }

    init {
        val modelPath = File(modelDir, model.modelFile).absolutePath
        val tokensPath = File(modelDir, model.tokensFile).absolutePath
        val lexiconPath = model.lexiconFile?.let { File(modelDir, it).absolutePath }.orEmpty()
        val ruleFsts = model.ruleFsts?.split(",")?.joinToString(",") {
            File(modelDir, it.trim()).absolutePath
        }.orEmpty()

        // No dataDir: sherpa-onnx >= 2.0 ignores it (and warns); EspeakPhonemizer reads it instead.
        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelPath,
            tokens = tokensPath,
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
        onStep("sherpa load model")
        tts = OfflineTts(null, config)
        val modelLang = tts.lang()
        if (model.frontend == TtsFrontend.ESPEAK && modelLang.isNotEmpty() && modelLang != model.espeakVoice) {
            Log.w(TAG, "${model.id} says espeak voice '$modelLang', catalog has '${model.espeakVoice}'")
        }
    }

    @Synchronized
    override fun generate(text: String, sid: Int, speed: Float): TtsAudio {
        val audio = tts.generate(text, sid, speed)
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun generateStream(
        text: String,
        sid: Int,
        speed: Float,
        isCancelled: () -> Boolean,
        onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
    ): Int {
        if (isCancelled()) return 0
        val config = if (model.frontend == TtsFrontend.ESPEAK) {
            onStep("espeak phonemize")
            val clauses = phonemizer?.clauses(text, model.espeakVoice) ?: return TtsEngine.PHONEMIZER_UNAVAILABLE
            val sentences = PiperPhonemes.sentences(clauses, model.espeakVoice)
            if (sentences.isEmpty()) return 0
            GenerationConfig(sid = sid, speed = speed, phonemeCodepoints = PiperPhonemes.toCodepoints(sentences))
        } else {
            GenerationConfig(sid = sid, speed = speed)
        }
        return try {
            onStep("sherpa generate")
            val rate = tts.sampleRate()
            var streamed = 0
            // With phonemeCodepoints set, sherpa-onnx ignores the text argument.
            val input = if (model.frontend == TtsFrontend.ESPEAK) "" else text
            val callback = SherpaChunkCallback { chunk ->
                if (isCancelled()) {
                    0
                } else {
                    onChunk(chunk, rate)
                    streamed += chunk.size
                    1
                }
            }
            val audio = tts.generateWithConfigAndCallback(input, config, callback)
            if (streamed == 0 && !isCancelled() && audio.samples.isNotEmpty()) {
                onChunk(audio.samples, audio.sampleRate)
                streamed = audio.samples.size
            }
            streamed
        } catch (e: Exception) {
            Log.e(TAG, "generateStream failed", e)
            TtsEngine.SYNTHESIS_FAILED
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
        private const val PRIME_TEXT = "a"
    }
}

/**
 * The chunk callback handed to sherpa-onnx. Its JNI code looks up `invoke([F)Ljava/lang/Integer;`
 * on this object by name, so it is a named class with that method (an invokedynamic lambda only
 * has the erased `invoke(Object)`), and keepRules/rules.keep stops R8 from renaming or dropping it.
 */
internal class SherpaChunkCallback(private val onSamples: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = onSamples(samples)
}

/**
 * Manages lazy-loading and lifecycle of [TtsEngine] instances for one active language.
 */
class TtsEngineSlot(
    private val factory: (TtsModel, File) -> TtsEngine
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
