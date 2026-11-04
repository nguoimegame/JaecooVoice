package com.example.jaecoovoice.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Speaks a real JaecooApp reply with the Vietnamese Piper model through sherpa-onnx 2.x and the
 * espeak-ng phonemizer. Needs the vi model installed (JaecooApp's Voice tab, or copied into
 * `files/tts_models/vi/vits-piper-vi_VN-vais1000-medium/` with `run-as`); skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class SherpaTtsEngineTest {

    @Test
    fun vietnameseWeatherSentenceProducesAudio() {
        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        assumeTrue(TtsModelStore.isInstalled(filesDir, TtsCatalog.VI_VAIS1000))

        val core = TtsCore(filesDir)
        var samples = 0
        var rate = 0
        var peak = 0f
        val started = System.nanoTime()
        val result = core.synthesize("vi", WEATHER, isCancelled = { false }) { chunk, sampleRate ->
            samples += chunk.size
            rate = sampleRate
            chunk.forEach { peak = maxOf(peak, kotlin.math.abs(it)) }
        }
        android.util.Log.i(
            "SherpaTtsEngineTest",
            "$result: $samples samples at $rate Hz, peak $peak, in ${(System.nanoTime() - started) / NS_PER_MS} ms"
        )
        core.release()

        assertEquals(TtsCore.SynthResult.Spoken, result)
        assertEquals(TtsCatalog.VI_VAIS1000.sampleRate, rate)
        assertTrue("only $samples samples", samples > rate)
        assertTrue("silent audio, peak $peak", peak > MIN_PEAK)
    }

    private companion object {
        const val WEATHER = "Vị trí hiện tại: Trời quang đãng, 25 độ C, cảm giác 32 độ C"
        const val NS_PER_MS = 1_000_000
        const val MIN_PEAK = 0.05f
    }
}
