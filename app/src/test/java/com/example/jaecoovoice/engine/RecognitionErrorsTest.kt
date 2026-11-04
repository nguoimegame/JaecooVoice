package com.example.jaecoovoice.engine

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionErrorsTest {

    @Test
    fun everyContractErrorHasAnAndroidCode() {
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, RecognitionErrors.toSpeechRecognizer(VoiceErrors.NO_SPEECH))
        assertEquals(SpeechRecognizer.ERROR_AUDIO, RecognitionErrors.toSpeechRecognizer(VoiceErrors.MIC_UNAVAILABLE))
        assertEquals(SpeechRecognizer.ERROR_AUDIO, RecognitionErrors.toSpeechRecognizer(VoiceErrors.MIC_SILENT))
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, RecognitionErrors.toSpeechRecognizer(VoiceErrors.NO_MIC_PERMISSION))
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RecognitionErrors.toSpeechRecognizer(VoiceErrors.BUSY))
        listOf(VoiceErrors.MODEL_MISSING, VoiceErrors.MODEL_LOAD_FAILED, VoiceErrors.RECOGNIZE_FAILED, VoiceErrors.LANGUAGE_UNSUPPORTED)
            .forEach { assertEquals(SpeechRecognizer.ERROR_SERVER, RecognitionErrors.toSpeechRecognizer(it)) }
        assertNull(RecognitionErrors.toSpeechRecognizer(VoiceErrors.CANCELLED))
    }

    @Test
    fun rmsDbIsClamped() {
        assertEquals(-2f, RecognitionErrors.rmsDb(0f), 0f)
        assertEquals(10f, RecognitionErrors.rmsDb(1f), 0f)
        assertTrue(RecognitionErrors.rmsDb(0.05f) in 3f..6f)
    }
}
