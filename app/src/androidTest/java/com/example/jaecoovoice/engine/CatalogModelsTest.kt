package com.example.jaecoovoice.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Installs one catalog model from an archive already on the device and transcribes a sample wav
 * with it, to prove the sha256, the unpacked file names and the engine config of its family.
 *
 * Needs instrumentation args `model` (a [VoiceModel.id]) and `language`, the archive at
 * `/data/local/tmp/<model id>.tar.bz2` and a 16 kHz mono 16-bit wav at `/data/local/tmp/t_<language>.wav`;
 * skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class CatalogModelsTest {

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - WAV_HEADER) / 2) { pcm.short / PCM_MAX }
    }

    @Test
    fun installsAndTranscribes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("model")
        val language = args.getString("language")
        assumeTrue(id != null && language != null)
        val model = ModelCatalog.all.first { it.id == id }
        val archive = File("/data/local/tmp/$id.tar.bz2")
        val wav = File("/data/local/tmp/t_$language.wav")
        assumeTrue(archive.exists() && wav.exists())

        val filesDir = instrumentation.targetContext.filesDir
        ModelStore.delete(filesDir, model)
        val install = ModelStore.install(filesDir, model, onProgress = {}, open = { archive.inputStream() })
        assertTrue("install failed: $install", install == ModelStore.InstallResult.Success)

        val loadStarted = System.nanoTime()
        val engine = SherpaSpeechEngine(model, ModelStore.dir(filesDir, model))
        val loadMs = (System.nanoTime() - loadStarted) / NS_PER_MS
        try {
            val started = System.nanoTime()
            val text = engine.transcribe(readWav(wav))
            val ms = (System.nanoTime() - started) / NS_PER_MS
            android.util.Log.i("CatalogModels", "RESULT ${model.id} [$language] load=${loadMs}ms decode=${ms}ms text='$text'")
            assertTrue("empty transcript", text.isNotBlank())
        } finally {
            engine.release()
            ModelStore.delete(filesDir, model)
        }
    }

    private companion object {
        const val WAV_HEADER = 44
        const val PCM_MAX = 32768f
        const val NS_PER_MS = 1_000_000
    }
}
