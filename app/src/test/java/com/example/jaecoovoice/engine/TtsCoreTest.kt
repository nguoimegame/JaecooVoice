package com.example.jaecoovoice.engine

import com.example.jaecoovoice.engine.TtsCore.SynthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsCoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeTtsEngine(
        val sampleRate: Int = 22050,
        var chunksToEmit: List<FloatArray> = listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f)),
        /** Overrides what generateStream returns (e.g. TtsEngine.PHONEMIZER_UNAVAILABLE). */
        val forcedResult: Int? = null
    ) : TtsEngine {
        var generateStreamCallCount = 0
        var released = false

        override fun generate(text: String, sid: Int, speed: Float): TtsAudio {
            return TtsAudio(floatArrayOf(0.1f), sampleRate)
        }

        override fun generateStream(
            text: String,
            sid: Int,
            speed: Float,
            isCancelled: () -> Boolean,
            onChunk: (samples: FloatArray, sampleRate: Int) -> Unit
        ): Int {
            generateStreamCallCount++
            forcedResult?.let { return it }
            var total = 0
            for (chunk in chunksToEmit) {
                if (isCancelled()) break
                onChunk(chunk, sampleRate)
                total += chunk.size
            }
            return total
        }

        override fun release() {
            released = true
        }
    }

    private fun markInstalled(filesDir: File, model: TtsModel): File {
        val dir = TtsModelStore.dir(filesDir, model).apply { mkdirs() }
        File(dir, model.modelFile).writeText("onnx")
        File(dir, model.tokensFile).writeText("tokens")
        if (model.frontend == TtsFrontend.ESPEAK) {
            File(TtsModelStore.sharedEspeakDataDir(filesDir).apply { mkdirs() }, "phontab").writeText("p")
        }
        model.lexiconFile?.let { File(dir, it).writeText("lexicon") }
        return dir
    }

    @Test
    fun returnsFalseWhenLanguageNotInstalled() {
        val fakeEngine = FakeTtsEngine()
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        assertFalse(core.isInstalled("vi"))
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Failed("tts model not installed: vi"), result)
        assertEquals(0, fakeEngine.generateStreamCallCount)
    }

    @Test
    fun streamsChunksWhenModelInstalled() {
        val fakeEngine = FakeTtsEngine()
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        val model = TtsCatalog.VI_VAIS1000
        markInstalled(temp.root, model)
        assertTrue(core.isInstalled("vi"))

        val receivedChunks = mutableListOf<FloatArray>()
        var receivedRate = 0
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { samples, rate ->
            receivedChunks += samples
            receivedRate = rate
        }

        assertEquals(SynthResult.Spoken, result)
        assertEquals(1, fakeEngine.generateStreamCallCount)
        assertEquals(2, receivedChunks.size)
        assertEquals(22050, receivedRate)
    }

    @Test
    fun abortsImmediatelyWhenCancelled() {
        val fakeEngine = FakeTtsEngine(
            chunksToEmit = listOf(
                floatArrayOf(0.1f),
                floatArrayOf(0.2f),
                floatArrayOf(0.3f)
            )
        )
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        markInstalled(temp.root, TtsCatalog.VI_VAIS1000)

        val cancelAfter = 1
        var chunkCount = 0
        val result = core.synthesize(
            language = "vi",
            text = "Xin chào",
            isCancelled = { chunkCount >= cancelAfter }
        ) { _, _ ->
            chunkCount++
        }

        assertEquals(SynthResult.Cancelled, result)
        assertEquals(1, chunkCount)
    }

    @Test
    fun releaseIfIdleReleasesOnlyAfterTimeout() {
        var currentTime = 1000L
        val fakeEngine = FakeTtsEngine()
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val timeoutMs = 5000L
        val core = TtsCore(
            filesDir = temp.root,
            slot = slot,
            idleTimeoutMs = timeoutMs,
            clock = { currentTime }
        )

        markInstalled(temp.root, TtsCatalog.VI_VAIS1000)
        core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertFalse(fakeEngine.released)

        // Advance 2 seconds (not yet timed out)
        currentTime += 2000L
        core.releaseIfIdle()
        assertFalse(fakeEngine.released)

        // Advance past timeout
        currentTime += 3500L
        core.releaseIfIdle()
        assertTrue(fakeEngine.released)
    }

    @Test
    fun releaseUnconditionallyReleasesSlot() {
        val fakeEngine = FakeTtsEngine()
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        markInstalled(temp.root, TtsCatalog.VI_VAIS1000)
        core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertFalse(fakeEngine.released)

        core.release()
        assertTrue(fakeEngine.released)
    }

    @Test
    fun returnsFalseWhenLanguageNotSupported() {
        val fakeEngine = FakeTtsEngine()
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        assertFalse(core.isInstalled("unsupported_lang"))
        val result = core.synthesize("unsupported_lang", "Hello", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Failed("tts model not installed: unsupported_lang"), result)
        assertEquals(0, fakeEngine.generateStreamCallCount)
    }

    @Test
    fun returnsFalseWhenEngineSlotFailsToLoad() {
        val slot = TtsEngineSlot(factory = { _, _ -> throw IllegalStateException("Failed to init") })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        markInstalled(temp.root, TtsCatalog.VI_VAIS1000)
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Failed("tts model failed to load: vi"), result)
    }

    private fun installedCore(engine: FakeTtsEngine): TtsCore {
        markInstalled(temp.root, TtsCatalog.VI_VAIS1000)
        return TtsCore(filesDir = temp.root, slot = TtsEngineSlot(factory = { _, _ -> engine }))
    }

    @Test
    fun zeroSamplesIsFailed() {
        val core = installedCore(FakeTtsEngine(chunksToEmit = emptyList()))
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Failed("tts produced no audio: vi"), result)
    }

    @Test
    fun phonemizerUnavailableIsFailed() {
        val core = installedCore(FakeTtsEngine(forcedResult = TtsEngine.PHONEMIZER_UNAVAILABLE))
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Failed("espeak phonemizer unavailable: vi"), result)
    }

    @Test
    fun engineErrorIsFailed() {
        val core = installedCore(FakeTtsEngine(forcedResult = TtsEngine.SYNTHESIS_FAILED))
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Failed("tts synthesis failed: vi"), result)
    }

    @Test
    fun cancelledIsNotFailure() {
        var chunks = 0
        val core = installedCore(FakeTtsEngine())
        val result = core.synthesize("vi", "Xin chào", isCancelled = { chunks >= 1 }) { _, _ -> chunks++ }
        assertEquals(SynthResult.Cancelled, result)
    }

    @Test
    fun cancelledWithNoAudioIsStillCancelled() {
        val core = installedCore(FakeTtsEngine(chunksToEmit = emptyList()))
        val result = core.synthesize("vi", "Xin chào", isCancelled = { true }) { _, _ -> }
        assertEquals(SynthResult.Cancelled, result)
    }

    @Test
    fun blankTextSkipsEngine() {
        val engine = FakeTtsEngine()
        val core = installedCore(engine)
        val result = core.synthesize("vi", "  ", isCancelled = { false }) { _, _ -> }
        assertEquals(SynthResult.Spoken, result)
        assertEquals(0, engine.generateStreamCallCount)
    }

    @Test
    fun resetForTestingReplacesSingletonInstance() {
        val fakeEngine = FakeTtsEngine()
        val slot = TtsEngineSlot(factory = { _, _ -> fakeEngine })
        val customCore = TtsCore(filesDir = temp.root, slot = slot)

        TtsCore.resetForTesting(customCore)
        // Verify resetForTesting clears it
        TtsCore.resetForTesting(null)
    }
}
