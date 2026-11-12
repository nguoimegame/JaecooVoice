package com.example.jaecoovoice.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** Turns one utterance (float -1..1, 16 kHz) into text. */
interface SpeechEngine {
    /**
     * Blocks the calling thread until decoding is done. [hotwords] are phrases the decoder should favour;
     * an engine that cannot use them ignores them.
     */
    fun transcribe(samples: FloatArray, hotwords: List<String> = emptyList()): String

    fun release()
}

/**
 * sherpa-onnx offline recognizer for one model of [ModelCatalog]; the model's family picks the
 * config. Building the recognizer takes about a second, so [EngineCore] keeps one; [transcribe]
 * is serialized.
 */
class SherpaSpeechEngine(
    model: VoiceModel,
    modelDir: File,
    numThreads: Int = THREADS,
    /** The model's BPE vocabulary ([HotwordVocab]); null = greedy search, no hotwords. */
    vocab: File? = null
) : SpeechEngine {

    private val decoding = decodingFor(model.family, vocab)

    /** Letters the model has tokens for; hotwords with any other letter are dropped before they reach the native code. */
    private val alphabet: Set<Char> = if (decoding.beam) HotwordFormat.alphabet(File(modelDir, ModelCatalog.TOKENS)) else emptySet()

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig(model, modelDir, numThreads).also {
                if (decoding.beam) {
                    it.modelingUnit = "bpe"
                    it.bpeVocab = vocab?.absolutePath.orEmpty()
                }
            },
            decodingMethod = decoding.method,
            maxActivePaths = MAX_ACTIVE_PATHS,
            hotwordsScore = HOTWORDS_SCORE
        )
    )

    @Synchronized
    override fun transcribe(samples: FloatArray, hotwords: List<String>): String {
        val encoded = if (decoding.beam && hotwords.isNotEmpty()) HotwordFormat.encode(hotwords, alphabet) else ""
        if (hotwords.isNotEmpty()) {
            Log.i(TAG, "hotwords sent=${hotwords.size} used=${encoded.lines().count { it.isNotEmpty() }} beam=${decoding.beam}")
        }
        val stream = if (encoded.isEmpty()) recognizer.createStream() else recognizer.createStream(encoded)
        return try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun release() {
        recognizer.release()
    }

    companion object {
        private const val TAG = "SherpaSpeechEngine"
        private const val THREADS = 2

        /** Beam width and the bonus a hotword token gets; the only knobs to turn when hotwords force wrong words. */
        private const val MAX_ACTIVE_PATHS = 4
        private const val HOTWORDS_SCORE = 1.5f
        private const val FEATURE_DIM = 80

        internal fun modelConfig(model: VoiceModel, dir: File, numThreads: Int): OfflineModelConfig {
            fun path(name: String) = File(dir, name).absolutePath
            val base = OfflineModelConfig(tokens = path(ModelCatalog.TOKENS), numThreads = numThreads, debug = false)
            val transducer = OfflineTransducerModelConfig(
                encoder = path(ModelCatalog.ENCODER),
                decoder = path(ModelCatalog.DECODER),
                joiner = path(ModelCatalog.JOINER)
            )
            return when (model.family) {
                ModelFamily.TRANSDUCER -> base.apply { this.transducer = transducer }
                ModelFamily.NEMO_TRANSDUCER -> base.apply {
                    this.transducer = transducer
                    modelType = "nemo_transducer"
                }
                ModelFamily.SENSE_VOICE -> base.apply {
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = path(ModelCatalog.MODEL),
                        language = model.language,
                        useInverseTextNormalization = true
                    )
                }
                ModelFamily.OMNILINGUAL_CTC -> base.apply {
                    omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path(ModelCatalog.MODEL))
                }
            }
        }
    }
}
