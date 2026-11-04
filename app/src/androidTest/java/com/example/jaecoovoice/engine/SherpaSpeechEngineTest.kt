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
 * Runs the real model on sample wavs (16 kHz mono 16-bit) to prove the native library and its
 * config load. Needs the model installed (JaecooApp's Voice tab → Download model) and wavs pushed
 * to `/data/local/tmp/vi_<n>.wav`; skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class SherpaSpeechEngineTest {

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - WAV_HEADER) / 2) { pcm.short / PCM_MAX }
    }

    @Test
    fun transcribesTheSampleWavs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(ModelStore.isInstalled(context.filesDir, ModelCatalog.VI_30M))
        val wavs = (0..2).map { File("/data/local/tmp/vi_$it.wav") }.filter { it.exists() }
        assumeTrue(wavs.isNotEmpty())

        val engine = SherpaSpeechEngine(ModelCatalog.VI_30M, ModelStore.dir(context.filesDir, ModelCatalog.VI_30M))
        try {
            for (wav in wavs) {
                val started = System.nanoTime()
                val text = engine.transcribe(readWav(wav))
                android.util.Log.i("SherpaTest", "${wav.name}: '$text' in ${(System.nanoTime() - started) / NS_PER_MS} ms")
                assertTrue("empty transcript for ${wav.name}", text.isNotBlank())
            }
        } finally {
            engine.release()
        }
    }

    private companion object {
        const val WAV_HEADER = 44
        const val PCM_MAX = 32768f
        const val NS_PER_MS = 1_000_000
    }
}
