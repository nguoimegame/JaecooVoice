package com.example.jaecoovoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.TextView
import com.example.jaecoovoice.engine.EngineCore
import com.example.jaecoovoice.engine.LanguageResolver
import com.example.jaecoovoice.engine.ListenSink
import com.example.jaecoovoice.engine.ModelCatalog
import com.example.jaecoovoice.engine.ModelStore
import com.example.jaecoovoice.ui.VoiceWaveView
import java.util.Locale

/**
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` for other apps: a listening pill over the calling app,
 * one utterance, then EXTRA_RESULTS with one sentence. Being on screen, it may always record.
 * Not a launcher entry: the app still has no icon.
 */
class RecognizeSpeechActivity : Activity() {

    private val core by lazy { EngineCore.get(this) }
    private lateinit var label: TextView
    private lateinit var waves: VoiceWaveView
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognize)
        label = findViewById(R.id.label)
        waves = findViewById(R.id.waves)
        intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)?.takeIf { it.isNotBlank() }?.let { label.text = it }
        findViewById<android.view.View>(R.id.root).setOnClickListener { cancel() }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else end(null)
    }

    /** Back closes the activity (RESULT_CANCELED by default); the listen is cancelled here. */
    override fun onDestroy() {
        if (!finished) core.cancel()
        super.onDestroy()
    }

    private fun start() {
        val installed = ModelCatalog.all.filter { ModelStore.isInstalled(filesDir, it) }.flatMap { it.languages }.toSet()
        val language = LanguageResolver.forOtherApp(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE), Locale.getDefault().language, installed)
        if (language == null) {
            end(null)
            return
        }
        core.listen(language, Sink())
    }

    private fun cancel() {
        core.cancel()
        end(null)
    }

    /** [text] null = cancelled or failed. Runs once. */
    private fun end(text: String?) {
        if (finished) return
        finished = true
        if (text == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)))
        }
        finish()
    }

    private inner class Sink : ListenSink {
        override fun onState(state: Int) = runOnUiThread {
            val recognizing = state == ListenSink.STATE_RECOGNIZING
            waves.thinking = recognizing
            if (recognizing) label.setText(R.string.recognizing)
        }
        override fun onLevel(rms: Float) {
            waves.level = rms
        }
        override fun onResult(text: String) = runOnUiThread { end(text) }
        override fun onError(code: Int, detail: String) = runOnUiThread { end(null) }
    }

    private companion object {
        const val REQUEST_MIC = 1
    }
}
