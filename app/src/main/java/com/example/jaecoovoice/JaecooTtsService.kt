package com.example.jaecoovoice

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.example.jaecoovoice.engine.TtsCatalog
import com.example.jaecoovoice.engine.TtsCore
import java.util.Locale

/**
 * Standard Android TextToSpeechService allowing third-party apps (e.g. Google Maps, Waze,
 * screen readers) to synthesize speech using JaecooVoice's offline Sherpa models.
 */
class JaecooTtsService : TextToSpeechService() {

    @Volatile
    private var stopRequested = false

    private val core by lazy { TtsCore.get(this) }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return PcmAudioConverter.checkLanguageAvailable(lang) { core.isInstalled(it) }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> {
        val defaultLang = Locale.getDefault().language
        val info = PcmAudioConverter.LANGUAGE_INFO[defaultLang]
            ?: PcmAudioConverter.LANGUAGE_INFO["vi"]
            ?: return arrayOf("vie", "VNM", "")
        return arrayOf(info.iso3Language, info.iso3Country, "")
    }

    override fun onStop() {
        stopRequested = true
    }

    @Suppress("DEPRECATION")
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val text = (request.charSequenceText?.toString() ?: request.text).orEmpty()
        val lang = request.language.takeUnless { it.isNullOrBlank() } ?: "vi"
        val normLang = PcmAudioConverter.normalizeLanguage(lang) ?: "vi"
        val sampleRate = TtsCatalog.forLanguage(normLang)?.sampleRate ?: DEFAULT_SAMPLE_RATE

        if (text.isBlank()) {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
        } else if (!core.isInstalled(normLang)) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
        } else if (!stopRequested) {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            streamPcmToCallback(normLang, text, callback)
        }
    }

    private fun streamPcmToCallback(normLang: String, text: String, callback: SynthesisCallback) {
        val maxBuffer = callback.maxBufferSize.coerceAtLeast(MIN_BUFFER_SIZE)
        val result = core.synthesize(
            language = normLang,
            text = text,
            isCancelled = { stopRequested }
        ) { samples, _ ->
            if (stopRequested) return@synthesize
            val bytes = PcmAudioConverter.floatTo16BitPcm(samples)
            var offset = 0
            while (offset < bytes.size && !stopRequested) {
                val chunkLen = minOf(maxBuffer, bytes.size - offset)
                val status = callback.audioAvailable(bytes, offset, chunkLen)
                if (status != TextToSpeech.SUCCESS) {
                    stopRequested = true
                    break
                }
                offset += chunkLen
            }
        }

        if (stopRequested) return
        when (result) {
            TtsCore.SynthResult.Spoken -> callback.done()
            TtsCore.SynthResult.Cancelled -> Unit
            is TtsCore.SynthResult.Failed -> {
                Log.w(TAG, result.reason)
                val code = if (result.reason.startsWith("tts model not installed")) {
                    TextToSpeech.ERROR_NOT_INSTALLED_YET
                } else {
                    TextToSpeech.ERROR_SYNTHESIS
                }
                callback.error(code)
            }
        }
    }

    override fun onGetVoices(): List<Voice> {
        return TtsCatalog.all.mapNotNull { model ->
            val info = PcmAudioConverter.LANGUAGE_INFO[model.language] ?: return@mapNotNull null
            Voice(
                model.id,
                info.locale,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                emptySet()
            )
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val match = TtsCatalog.all.any { it.id == voiceName }
        return if (match) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        return onIsValidVoiceName(voiceName)
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val normLang = PcmAudioConverter.normalizeLanguage(lang) ?: return null
        return TtsCatalog.forLanguage(normLang)?.id
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 22050
        private const val MIN_BUFFER_SIZE = 256
        private const val TAG = "SherpaTtsEngine"
    }
}

data class LanguageLocaleInfo(
    val iso3Language: String,
    val iso3Country: String,
    val locale: Locale
)

object PcmAudioConverter {
    const val MAX_AMPLITUDE = 32767.0f

    val LANGUAGE_INFO: Map<String, LanguageLocaleInfo> = mapOf(
        "vi" to LanguageLocaleInfo("vie", "VNM", Locale.forLanguageTag("vi-VN")),
        "en" to LanguageLocaleInfo("eng", "USA", Locale.forLanguageTag("en-US")),
        "ru" to LanguageLocaleInfo("rus", "RUS", Locale.forLanguageTag("ru-RU")),
        "es" to LanguageLocaleInfo("spa", "ESP", Locale.forLanguageTag("es-ES")),
        "fr" to LanguageLocaleInfo("fra", "FRA", Locale.forLanguageTag("fr-FR")),
        "zh" to LanguageLocaleInfo("zho", "CHN", Locale.forLanguageTag("zh-CN")),
        "ar" to LanguageLocaleInfo("ara", "JOR", Locale.forLanguageTag("ar-JO")),
        "pt" to LanguageLocaleInfo("por", "BRA", Locale.forLanguageTag("pt-BR")),
        "th" to LanguageLocaleInfo("tha", "THA", Locale.forLanguageTag("th-TH")),
        "id" to LanguageLocaleInfo("ind", "IDN", Locale.forLanguageTag("id-ID")),
        "kk" to LanguageLocaleInfo("kaz", "KAZ", Locale.forLanguageTag("kk-KZ"))
    )

    private val THREE_TO_TWO_LETTER = mapOf(
        "vie" to "vi",
        "eng" to "en",
        "rus" to "ru",
        "spa" to "es",
        "fra" to "fr",
        "fre" to "fr",
        "zho" to "zh",
        "chi" to "zh",
        "ara" to "ar",
        "por" to "pt",
        "tha" to "th",
        "ind" to "id",
        "kaz" to "kk"
    )

    fun floatTo16BitPcm(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        var byteIdx = 0
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val shortVal = (clamped * MAX_AMPLITUDE).toInt().toShort()
            val intVal = shortVal.toInt()
            bytes[byteIdx++] = (intVal and 0xFF).toByte()
            bytes[byteIdx++] = ((intVal shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun normalizeLanguage(lang: String?): String? {
        val token = lang?.trim()
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.lowercase(Locale.ROOT)
            ?.ifEmpty { null }
            ?: return null

        val candidate = when {
            token == "in" -> "id"
            token in TtsCatalog.languages() -> token
            token in THREE_TO_TWO_LETTER -> THREE_TO_TWO_LETTER[token]
            else -> findByLocale(token)
        }
        return candidate?.takeIf { it in TtsCatalog.languages() }
    }

    private fun findByLocale(token: String): String? = try {
        Locale.getAvailableLocales().firstOrNull {
            it.isO3Language.equals(token, ignoreCase = true) || it.language.equals(token, ignoreCase = true)
        }?.language?.let { if (it == "in") "id" else it }
    } catch (_: Exception) {
        null
    }

    fun checkLanguageAvailable(lang: String?, isInstalled: (String) -> Boolean): Int {
        val normLang = normalizeLanguage(lang)
        return when {
            normLang == null || TtsCatalog.forLanguage(normLang) == null -> TextToSpeech.LANG_NOT_SUPPORTED
            isInstalled(normLang) -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_MISSING_DATA
        }
    }
}
