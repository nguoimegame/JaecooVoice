package com.example.jaecoovoice.engine

/** How text becomes model tokens. */
enum class TtsFrontend {
    /** Piper: JaecooVoice phonemizes with espeak-ng (EspeakPhonemizer) and passes codepoints. */
    ESPEAK,

    /** sherpa-onnx turns the text into tokens itself (lexicon, or characters for MMS). */
    TEXT
}

/**
 * One downloadable TTS model: a `.tar.bz2` archive containing ONNX model files,
 * tokens, and optional espeak-ng-data or lexicon files.
 */
data class TtsModel(
    val id: String,
    val language: String,
    val label: String,
    val url: String,
    val sizeBytes: Long,
    val archiveRoot: String,
    val modelFile: String,
    val tokensFile: String = "tokens.txt",
    val dataDir: String? = "espeak-ng-data",
    val lexiconFile: String? = null,
    val ruleFsts: String? = null,
    val sampleRate: Int = 22050,
    val sha256: String = "",
    val frontend: TtsFrontend = TtsFrontend.ESPEAK,
    /** espeak-ng voice for [TtsFrontend.ESPEAK]; "" for [TtsFrontend.TEXT]. */
    val espeakVoice: String = ""
)

/**
 * Catalog of offline TTS models for the 11 supported languages.
 * Models are sourced from official sherpa-onnx releases.
 */
object TtsCatalog {

    private const val ARCHIVE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    val VI_VAIS1000 = TtsModel(
        id = "vits-piper-vi_VN-vais1000-medium",
        language = "vi",
        label = "Piper tiếng Việt (vais1000)",
        url = ARCHIVE_BASE + "vits-piper-vi_VN-vais1000-medium.tar.bz2",
        sizeBytes = 67_154_040L,
        archiveRoot = "vits-piper-vi_VN-vais1000-medium",
        modelFile = "vi_VN-vais1000-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "vi"
    )

    val EN_LESSAC = TtsModel(
        id = "vits-piper-en_US-lessac-medium",
        language = "en",
        label = "Piper English (lessac)",
        url = ARCHIVE_BASE + "vits-piper-en_US-lessac-medium.tar.bz2",
        sizeBytes = 67_230_653L,
        archiveRoot = "vits-piper-en_US-lessac-medium",
        modelFile = "en_US-lessac-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "en-us"
    )

    val RU_DMITRI = TtsModel(
        id = "vits-piper-ru_RU-dmitri-medium",
        language = "ru",
        label = "Piper русский (dmitri)",
        url = ARCHIVE_BASE + "vits-piper-ru_RU-dmitri-medium.tar.bz2",
        sizeBytes = 67_188_551L,
        archiveRoot = "vits-piper-ru_RU-dmitri-medium",
        modelFile = "ru_RU-dmitri-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "ru"
    )

    val ES_SHARVARD = TtsModel(
        id = "vits-piper-es_ES-sharvard-medium",
        language = "es",
        label = "Piper español (sharvard)",
        url = ARCHIVE_BASE + "vits-piper-es_ES-sharvard-medium.tar.bz2",
        sizeBytes = 80_318_184L,
        archiveRoot = "vits-piper-es_ES-sharvard-medium",
        modelFile = "es_ES-sharvard-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "es"
    )

    val FR_SIWIS = TtsModel(
        id = "vits-piper-fr_FR-siwis-medium",
        language = "fr",
        label = "Piper français (siwis)",
        url = ARCHIVE_BASE + "vits-piper-fr_FR-siwis-medium.tar.bz2",
        sizeBytes = 67_207_459L,
        archiveRoot = "vits-piper-fr_FR-siwis-medium",
        modelFile = "fr_FR-siwis-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "fr-fr"
    )

    val ZH_AISHELL3 = TtsModel(
        id = "vits-zh-aishell3",
        language = "zh",
        label = "VITS 中文 (aishell3)",
        url = ARCHIVE_BASE + "vits-zh-aishell3.tar.bz2",
        sizeBytes = 146_922_607L,
        archiveRoot = "vits-zh-aishell3",
        modelFile = "vits-aishell3.onnx",
        tokensFile = "tokens.txt",
        dataDir = null,
        lexiconFile = "lexicon.txt",
        ruleFsts = "date.fst,number.fst,phone.fst",
        sampleRate = 22050,
        frontend = TtsFrontend.TEXT
    )

    val AR_KAREEM = TtsModel(
        id = "vits-piper-ar_JO-kareem-low",
        language = "ar",
        label = "Piper العربية (kareem)",
        url = ARCHIVE_BASE + "vits-piper-ar_JO-kareem-low.tar.bz2",
        sizeBytes = 67_117_397L,
        archiveRoot = "vits-piper-ar_JO-kareem-low",
        modelFile = "ar_JO-kareem-low.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 16000,
        espeakVoice = "ar"
    )

    val PT_FABER = TtsModel(
        id = "vits-piper-pt_BR-faber-medium",
        language = "pt",
        label = "Piper português (faber)",
        url = ARCHIVE_BASE + "vits-piper-pt_BR-faber-medium.tar.bz2",
        sizeBytes = 67_183_065L,
        archiveRoot = "vits-piper-pt_BR-faber-medium",
        modelFile = "pt_BR-faber-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "pt-br"
    )

    val TH_MMS = TtsModel(
        id = "vits-mms-tha",
        language = "th",
        label = "VITS ภาษาไทย (mms)",
        url = ARCHIVE_BASE + "vits-mms-tha.tar.bz2",
        sizeBytes = 107_763_751L,
        archiveRoot = "vits-mms-tha",
        modelFile = "model.onnx",
        tokensFile = "tokens.txt",
        dataDir = null,
        sampleRate = 16000,
        frontend = TtsFrontend.TEXT
    )

    val ID_NEWS = TtsModel(
        id = "vits-piper-id_ID-news_tts-medium",
        language = "id",
        label = "Piper bahasa Indonesia",
        url = ARCHIVE_BASE + "vits-piper-id_ID-news_tts-medium.tar.bz2",
        sizeBytes = 67_243_848L,
        archiveRoot = "vits-piper-id_ID-news_tts-medium",
        modelFile = "id_ID-news_tts-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050,
        espeakVoice = "id"
    )

    val KK_ISEKE = TtsModel(
        id = "vits-piper-kk_KZ-iseke-x_low",
        language = "kk",
        label = "Piper қазақша (iseke)",
        url = ARCHIVE_BASE + "vits-piper-kk_KZ-iseke-x_low.tar.bz2",
        sizeBytes = 26_517_675L,
        archiveRoot = "vits-piper-kk_KZ-iseke-x_low",
        modelFile = "kk_KZ-iseke-x_low.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 16000,
        espeakVoice = "kk"
    )

    val all: List<TtsModel> = listOf(
        VI_VAIS1000,
        EN_LESSAC,
        RU_DMITRI,
        ES_SHARVARD,
        FR_SIWIS,
        ZH_AISHELL3,
        AR_KAREEM,
        PT_FABER,
        TH_MMS,
        ID_NEWS,
        KK_ISEKE
    )

    fun forLanguage(language: String): TtsModel? = all.firstOrNull { it.language == language }

    fun languages(): List<String> = all.map { it.language }
}
