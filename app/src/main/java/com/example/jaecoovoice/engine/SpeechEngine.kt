package com.example.jaecoovoice.engine

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
    /** Blocks the calling thread until decoding is done. */
    fun transcribe(samples: FloatArray): String

    fun release()
}

/**
 * sherpa-onnx offline recognizer for one model of [ModelCatalog]; the model's family picks the
 * config. Building the recognizer takes about a second, so [EngineCore] keeps one; [transcribe]
 * is serialized.
 */
class SherpaSpeechEngine(model: VoiceModel, modelDir: File, numThreads: Int = THREADS) : SpeechEngine {

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig(model, modelDir, numThreads),
            decodingMethod = "greedy_search"
        )
    )

    @Synchronized
    override fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
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
        private const val THREADS = 2
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
