package com.example.jaecoovoice

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The system default recognizer answers: Settings.Secure.voice_recognition_service names a working
 * service (JaecooApp sets it to JaecooRecognitionService). ERROR_CLIENT means the setting is empty or
 * the bind failed. On a silent emulator mic a speech timeout is a pass.
 */
@RunWith(AndroidJUnit4::class)
class DefaultRecognizerTest {

    @get:Rule
    val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun defaultRecognizerAnswers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val done = CountDownLatch(1)
        var error: Int? = null
        var results: List<String>? = null
        val main = Handler(Looper.getMainLooper())
        lateinit var recognizer: SpeechRecognizer
        main.post {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(code: Int) {
                    error = code
                    done.countDown()
                }
                override fun onResults(bundle: Bundle?) {
                    results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    done.countDown()
                }
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
        }
        assertTrue("no answer from the default recognizer", done.await(WAIT_S, TimeUnit.SECONDS))
        main.post { recognizer.destroy() }
        assertNotEquals("voice_recognition_service is empty or the bind failed", SpeechRecognizer.ERROR_CLIENT, error)
        android.util.Log.i("DefaultRecognizerTest", "error=$error results=$results")
    }

    private companion object {
        const val WAIT_S = 20L
    }
}
