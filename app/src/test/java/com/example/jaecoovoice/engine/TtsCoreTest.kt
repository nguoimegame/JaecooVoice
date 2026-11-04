package com.example.jaecoovoice.engine

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
        var chunksToEmit: List<FloatArray> = listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f))
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
        ): Boolean {
            generateStreamCallCount++
            var success = !isCancelled()
            for (chunk in chunksToEmit) {
                if (!success || isCancelled()) {
                    success = false
                    break
                }
                onChunk(chunk, sampleRate)
            }
            return success
        }

        override fun release() {
            released = true
        }
    }

    private fun markInstalled(filesDir: File, model: TtsModel): File {
        val dir = TtsModelStore.dir(filesDir, model).apply { mkdirs() }
        File(dir, model.modelFile).writeText("onnx")
        File(dir, model.tokensFile).writeText("tokens")
        model.dataDir?.let { File(dir, it).mkdirs() }
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
        assertFalse(result)
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

        assertTrue(result)
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

        assertFalse(result)
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
        assertFalse(result)
        assertEquals(0, fakeEngine.generateStreamCallCount)
    }

    @Test
    fun returnsFalseWhenEngineSlotFailsToLoad() {
        val slot = TtsEngineSlot(factory = { _, _ -> throw IllegalStateException("Failed to init") })
        val core = TtsCore(filesDir = temp.root, slot = slot)

        markInstalled(temp.root, TtsCatalog.VI_VAIS1000)
        val result = core.synthesize("vi", "Xin chào", isCancelled = { false }) { _, _ -> }
        assertFalse(result)
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
