package com.example.jaecoovoice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.example.jaecoovoice.engine.TtsCatalog
import com.example.jaecoovoice.engine.TtsModelStore
import java.io.File
import java.util.ArrayList

/**
 * Activity responding to `TextToSpeech.Engine.ACTION_CHECK_TTS_DATA`.
 * Checks which language models are downloaded and returns available/unavailable lists to the caller.
 */
@Suppress("DEPRECATION")
class CheckTtsDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val availableLangs = ArrayList<String>()
        val unavailableLangs = ArrayList<String>()

        for (model in TtsCatalog.all) {
            val info = PcmAudioConverter.LANGUAGE_INFO[model.language]
            val tag = if (info != null) {
                "${info.iso3Language}-${info.iso3Country}"
            } else {
                "${model.language}-${model.language.uppercase()}"
            }
            if (TtsModelStore.isInstalled(filesDir, model)) {
                availableLangs.add(tag)
            } else {
                unavailableLangs.add(tag)
            }
        }

        val resultIntent = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableLangs)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableLangs)
            putExtra(
                TextToSpeech.Engine.EXTRA_VOICE_DATA_ROOT_DIRECTORY,
                File(filesDir, "tts_models").absolutePath
            )
        }

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultIntent)
        finish()
    }
}
