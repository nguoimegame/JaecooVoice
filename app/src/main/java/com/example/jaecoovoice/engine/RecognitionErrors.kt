package com.example.jaecoovoice.engine

import android.speech.SpeechRecognizer
import kotlin.math.log10
import kotlin.math.max

/** Our listen results in the words of `android.speech.RecognitionService`. */
object RecognitionErrors {

    private const val MIN_RMS = 1e-4f
    private const val DB_PER_DECADE = 20f
    private const val DB_OFFSET = 50f
    private const val DB_SCALE = 5f
    private const val MIN_DB = -2f
    private const val MAX_DB = 10f

    /** The SpeechRecognizer error for a [VoiceErrors] code; null for a cancel, which gets no callback. */
    fun toSpeechRecognizer(code: Int): Int? = when (code) {
        VoiceErrors.CANCELLED -> null
        VoiceErrors.NO_SPEECH -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        VoiceErrors.MIC_UNAVAILABLE, VoiceErrors.MIC_SILENT -> SpeechRecognizer.ERROR_AUDIO
        VoiceErrors.NO_MIC_PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        VoiceErrors.BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        else -> SpeechRecognizer.ERROR_SERVER
    }

    /**
     * RMS 0–1 as the "dB" apps expect from onRmsChanged: Google's recognizer reports roughly −2
     * (silence) to 10 (loud), and apps size their mic animation by that range.
     */
    fun rmsDb(rms: Float): Float = ((DB_PER_DECADE * log10(max(rms, MIN_RMS)) + DB_OFFSET) / DB_SCALE).coerceIn(MIN_DB, MAX_DB)
}
