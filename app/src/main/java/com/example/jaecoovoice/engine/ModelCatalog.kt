package com.example.jaecoovoice.engine

/** Which sherpa-onnx recognizer config a model needs ([SherpaSpeechEngine] builds it). */
enum class ModelFamily { TRANSDUCER, NEMO_TRANSDUCER, SENSE_VOICE, OMNILINGUAL_CTC }

/**
 * One downloadable recognition model: a `.tar.bz2` whose top folder [archiveRoot] holds [files].
 * [language] is the BCP-47 language part (`vi`, `en`…) the model recognizes.
 */
data class VoiceModel(
    val id: String,
    val language: String,
    val label: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val archiveRoot: String,
    val files: Set<String>,
    /** Archive file name → name under [files], for archives that name the files differently. */
    val archiveNames: Map<String, String> = emptyMap(),
    val family: ModelFamily = ModelFamily.TRANSDUCER,
    /** Other languages this one model also recognizes; it is downloaded and stored once. */
    val otherLanguages: Set<String> = emptySet()
) {
    val languages: Set<String> get() = setOf(language) + otherLanguages
}

/**
 * The models this build knows. Adding a language means adding an entry and shipping a new
 * JaecooVoice; the contract with JaecooApp does not change.
 */
object ModelCatalog {

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    /** The single model file of the SenseVoice and Omnilingual families. */
    const val MODEL = "model.int8.onnx"

    private const val ARCHIVE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private val TRANSDUCER_FILES = setOf(ENCODER, DECODER, JOINER, TOKENS)
    private val SINGLE_MODEL_FILES = setOf(MODEL, TOKENS)

    /** NeMo archives ship the int8 decoder under a different name than the zipformer ones. */
    private val NEMO_NAMES = mapOf(
        ENCODER to ENCODER,
        "decoder.int8.onnx" to DECODER,
        JOINER to JOINER,
        TOKENS to TOKENS
    )

    /** Vietnamese zipformer 30M int8 from k2-fsa/sherpa-onnx (Apache-2.0). */
    val VI_30M = VoiceModel(
        id = "zipformer-vi-30M-int8-2026-02-09",
        language = "vi",
        label = "Zipformer tiếng Việt 30M",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2",
        sha256 = "da8b637947091829d7ee9eda23da2a4ec7caa399233a3f4e34eb719fb2ea6b9b",
        sizeBytes = 26_442_384L,
        archiveRoot = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
        files = setOf(ENCODER, DECODER, JOINER, TOKENS)
    )

    /** English zipformer small from k2-fsa/sherpa-onnx (Apache-2.0); int8 encoder and joiner. */
    val EN_SMALL = VoiceModel(
        id = "zipformer-small-en-2023-06-26",
        language = "en",
        label = "Zipformer English small",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-zipformer-small-en-2023-06-26.tar.bz2",
        sha256 = "c8bff1091c26c49731cddbcd60ef18061142ea11523df1b73bf1b14451b9c15e",
        sizeBytes = 112_232_184L,
        archiveRoot = "sherpa-onnx-zipformer-small-en-2023-06-26",
        files = setOf(ENCODER, DECODER, JOINER, TOKENS),
        archiveNames = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to ENCODER,
            "decoder-epoch-99-avg-1.onnx" to DECODER,
            "joiner-epoch-99-avg-1.int8.onnx" to JOINER,
            TOKENS to TOKENS
        )
    )

    /** Russian zipformer int8 from k2-fsa/sherpa-onnx. */
    val RU_ZIPFORMER = VoiceModel(
        id = "zipformer-ru-int8-2025-04-20",
        language = "ru",
        label = "Zipformer русский",
        url = ARCHIVE_BASE + "sherpa-onnx-zipformer-ru-int8-2025-04-20.tar.bz2",
        sha256 = "d6a651569aacc9a177259fa54705dd76acae23f6a4d62ea6797bd220d4b57163",
        sizeBytes = 60_239_942L,
        archiveRoot = "sherpa-onnx-zipformer-ru-int8-2025-04-20",
        files = TRANSDUCER_FILES
    )

    /** Spanish NeMo FastConformer transducer int8 (CC-BY-4.0, NVIDIA). */
    val ES_NEMO = VoiceModel(
        id = "nemo-fast-conformer-transducer-es-1424-int8",
        language = "es",
        label = "FastConformer español",
        url = ARCHIVE_BASE + "sherpa-onnx-nemo-fast-conformer-transducer-es-1424-int8.tar.bz2",
        sha256 = "d6d29ffd560c366da1b177b71e79d8733a9a9910dd1ace282d9bfae94e056210",
        sizeBytes = 107_246_685L,
        archiveRoot = "sherpa-onnx-nemo-fast-conformer-transducer-es-1424-int8",
        files = TRANSDUCER_FILES,
        archiveNames = NEMO_NAMES,
        family = ModelFamily.NEMO_TRANSDUCER
    )

    /** French (and en/de/es) NeMo FastConformer transducer int8 (CC-BY-4.0, NVIDIA). */
    val FR_NEMO = VoiceModel(
        id = "nemo-fast-conformer-transducer-en-de-es-fr-14288-int8",
        language = "fr",
        label = "FastConformer français",
        url = ARCHIVE_BASE + "sherpa-onnx-nemo-fast-conformer-transducer-en-de-es-fr-14288-int8.tar.bz2",
        sha256 = "69416bd0845a8c1e78a110e7a6434fcf94af0c0c905d4d4d288e42d7b013af74",
        sizeBytes = 106_902_947L,
        archiveRoot = "sherpa-onnx-nemo-fast-conformer-transducer-en-de-es-fr-14288-int8",
        files = TRANSDUCER_FILES,
        archiveNames = NEMO_NAMES,
        family = ModelFamily.NEMO_TRANSDUCER
    )

    /** Chinese (also en, ja, ko, yue) SenseVoice int8 from FunASR. */
    val ZH_SENSE_VOICE = VoiceModel(
        id = "sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        language = "zh",
        label = "SenseVoice 中文",
        url = ARCHIVE_BASE + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
        sha256 = "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63",
        sizeBytes = 165_783_878L,
        archiveRoot = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        files = SINGLE_MODEL_FILES,
        family = ModelFamily.SENSE_VOICE
    )

    /** Omnilingual ASR 300M CTC int8 (Apache-2.0, Meta): one download for the languages without a model of their own. */
    val OMNILINGUAL = VoiceModel(
        id = "omnilingual-asr-300M-ctc-v2-int8-2026-02-05",
        language = "ar",
        label = "Omnilingual ASR 300M",
        url = ARCHIVE_BASE + "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2",
        sha256 = "951b32409aade32bd525310bb39e9666773ba3fc611a39e817f620936d76c631",
        sizeBytes = 292_313_120L,
        archiveRoot = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05",
        files = SINGLE_MODEL_FILES,
        family = ModelFamily.OMNILINGUAL_CTC,
        otherLanguages = setOf("pt", "th", "id", "kk")
    )

    val all: List<VoiceModel> = listOf(VI_30M, EN_SMALL, RU_ZIPFORMER, ES_NEMO, FR_NEMO, ZH_SENSE_VOICE, OMNILINGUAL)

    fun forLanguage(language: String): VoiceModel? = all.firstOrNull { language in it.languages }

    fun languages(): List<String> = all.flatMap { it.languages }.distinct()
}
