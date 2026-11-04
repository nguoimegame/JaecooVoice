package com.example.jaecoovoice

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.jaecoovoice.engine.EngineCore
import com.example.jaecoovoice.engine.LanguageResolver
import com.example.jaecoovoice.engine.ListenSink
import com.example.jaecoovoice.engine.ModelCatalog
import com.example.jaecoovoice.engine.ModelStore
import com.example.jaecoovoice.engine.RecognitionErrors
import java.util.Locale

/**
 * The system speech recognizer for other apps (`SpeechRecognizer`). JaecooApp makes it the default
 * by writing `voice_recognition_service`. Same [EngineCore] as JaecooApp's listens, so one at a time.
 *
 * Android 11 binds this with BIND_INCLUDE_CAPABILITIES, so the calling app, being on screen, lends
 * this process its right to record. Android checks the caller's RECORD_AUDIO itself.
 *
 * Declared in the manifest and named in `voice_recognition_service`, so it stays in the root package.
 */
class JaecooRecognitionService : RecognitionService() {

    private val core by lazy { EngineCore.get(this) }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val callback = listener ?: return
        val installed = ModelCatalog.all.filter { ModelStore.isInstalled(filesDir, it) }.flatMap { it.languages }.toSet()
        val language = LanguageResolver.forOtherApp(
            recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
            Locale.getDefault().language,
            installed
        )
        if (language == null) {
            send { callback.error(SpeechRecognizer.ERROR_SERVER) }
            return
        }
        core.listen(language, CallbackSink(callback))
    }

    override fun onStopListening(listener: Callback?) {
        // The model has no outside endpoint: stopping early is the same as cancelling.
        core.cancel()
    }

    override fun onCancel(listener: Callback?) {
        core.cancel()
    }

    private inner class CallbackSink(private val callback: Callback) : ListenSink {
        override fun onReady() = send { callback.readyForSpeech(Bundle()) }
        override fun onSpeechStart() = send { callback.beginningOfSpeech() }
        override fun onEnd() = send { callback.endOfSpeech() }
        override fun onState(state: Int) = Unit
        override fun onLevel(rms: Float) = send { callback.rmsChanged(RecognitionErrors.rmsDb(rms)) }
        override fun onResult(text: String) = send {
            callback.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
        }
        override fun onError(code: Int, detail: String) {
            Log.w(TAG, "listen failed for another app: code=$code detail=$detail")
            RecognitionErrors.toSpeechRecognizer(code)?.let { error -> send { callback.error(error) } }
        }
    }

    /** The calling app may be gone; then the listen is cancelled. */
    private fun send(call: () -> Unit) {
        try {
            call()
        } catch (e: RemoteException) {
            Log.w(TAG, "caller is gone", e)
            core.cancel()
        }
    }

    private companion object {
        const val TAG = "RecognitionService"
    }
}
