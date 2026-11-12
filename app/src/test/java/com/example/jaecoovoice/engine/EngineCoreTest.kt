package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

class EngineCoreTest {

    private class FakeRecorder(
        private val result: RecordResult,
        override val peak: Float = 0.05f,
        private val pauseSnapshots: List<FloatArray> = emptyList(),
        private val during: () -> Unit = {}
    ) : UtteranceRecorder {
        var cancelled = false
        override fun record(onLevel: (Float) -> Unit, onSpeechStart: () -> Unit, onPause: (FloatArray) -> Unit): RecordResult {
            onLevel(0.3f)
            onSpeechStart()
            pauseSnapshots.forEach(onPause)
            during()
            return if (cancelled) RecordResult.Cancelled else result
        }
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeEngine(private val text: String, private val during: () -> Unit = {}) : SpeechEngine {
        var released = false
        override fun transcribe(samples: FloatArray): String {
            during()
            return text
        }
        override fun release() {
            released = true
        }
    }

    private class RecordingSink : ListenSink {
        val events = mutableListOf<String>()
        override fun onReady() {
            events += "ready"
        }
        override fun onState(state: Int) {
            events += "state$state"
        }
        override fun onLevel(rms: Float) {
            events += "level"
        }
        override fun onResult(text: String) {
            events += "result:$text"
        }
        override fun onError(code: Int, detail: String) {
            events += "error:$code:$detail"
        }

        val terminals get() = events.filter { it.startsWith("result:") || it.startsWith("error:") }

        fun assertOnlyError(code: Int, detailContains: String = "") {
            assertEquals(events.toString(), 1, terminals.size)
            val terminal = terminals.single()
            assertTrue(terminal, terminal.startsWith("error:$code:"))
            assertTrue(terminal, terminal.contains(detailContains))
        }
    }

    private class MemoryStore(var index: Int = 0) : MicSourceStore {
        override fun get() = index
        override fun set(index: Int) {
            this.index = index
        }
    }

    private val audio = RecordResult.Audio(floatArrayOf(0.1f, 0.2f))
    private val now = Executor { it.run() }
    private val store = MemoryStore()
    private var recorderFactoryCalls = 0
    private var engines = mutableListOf<FakeEngine>()

    @Suppress("LongParameterList")
    private fun core(
        recorder: () -> UtteranceRecorder = { FakeRecorder(audio) },
        engine: () -> SpeechEngine = { FakeEngine(" tăng gió ") },
        executor: Executor = now,
        decodeExecutor: Executor = now,
        installed: Boolean = true,
        permission: Boolean = true,
        dump: UtteranceDump = UtteranceDump.NONE
    ) = EngineCore(
        executor = executor,
        decodeExecutor = decodeExecutor,
        modelFor = ModelCatalog::forLanguage,
        installedDir = { if (installed) File("models") else null },
        engineFactory = { _, _ -> engine().also { if (it is FakeEngine) engines += it } },
        mic = EngineCore.MicSetup(
            sources = store,
            hasPermission = { permission },
            recorderFactory = { _ ->
                recorderFactoryCalls++
                recorder()
            }
        ),
        dump = dump
    )

    private fun EngineCore.listenOnce(language: String = "vi"): RecordingSink = RecordingSink().also { listen(language, it) }

    @Test
    fun heardUtteranceIsHandedToTheDump() {
        val saved = mutableListOf<Pair<Int, String>>()
        core(dump = { samples, text -> saved += samples.size to text }).listenOnce()
        assertEquals(listOf(audio.samples.size to "tăng gió"), saved)
    }

    @Test
    fun heardSentenceEndsWithOneResult() {
        val sink = core().listenOnce()
        assertEquals(listOf("ready", "state0", "state1", "result:tăng gió"), sink.events.filterNot { it == "level" })
        assertTrue(sink.events.contains("level"))
    }

    @Test
    fun unknownLanguageIsUnsupported() = core().listenOnce("de").assertOnlyError(VoiceErrors.LANGUAGE_UNSUPPORTED)

    @Test
    fun missingModelStopsBeforeTheMic() {
        core(installed = false).listenOnce().assertOnlyError(VoiceErrors.MODEL_MISSING)
        assertEquals(0, recorderFactoryCalls)
    }

    @Test
    fun noPermissionStopsBeforeTheMic() {
        core(permission = false).listenOnce().assertOnlyError(VoiceErrors.NO_MIC_PERMISSION)
        assertEquals(0, recorderFactoryCalls)
    }

    @Test
    fun engineThatThrowsIsALoadFailure() {
        val core = core(engine = { error("bad onnx") })
        core.listenOnce().assertOnlyError(VoiceErrors.MODEL_LOAD_FAILED, detailContains = "bad onnx")
        assertEquals("IllegalStateException: bad onnx", core.loadError("vi"))
        assertEquals(0, recorderFactoryCalls)
    }

    @Test
    fun aLaterGoodLoadClearsTheLoadError() {
        var fail = true
        val core = core(engine = { if (fail) error("bad") else FakeEngine("x") })
        core.listenOnce()
        fail = false
        core.listenOnce()
        assertEquals(null, core.loadError("vi"))
    }

    @Test
    fun blankTranscriptIsNoSpeech() = core(engine = { FakeEngine("   ") }).listenOnce().assertOnlyError(VoiceErrors.NO_SPEECH)

    @Test
    fun silentMicReportsSourceAndRotates() {
        val sink = core(recorder = { FakeRecorder(RecordResult.Failed("zeros", silent = true), peak = 0f) }).listenOnce()
        sink.assertOnlyError(VoiceErrors.MIC_SILENT, detailContains = "source=VOICE_RECOGNITION peak=0.0000")
        assertEquals(1, store.index)
    }

    @Test
    fun noSpeechOnADeadMicRotates() {
        core(recorder = { FakeRecorder(RecordResult.NoSpeech, peak = 0.001f) }).listenOnce().assertOnlyError(VoiceErrors.NO_SPEECH)
        assertEquals(1, store.index)
    }

    @Test
    fun noSpeechWithLiveMicKeepsTheSource() {
        val sink = core(recorder = { FakeRecorder(RecordResult.NoSpeech, peak = 0.05f) }).listenOnce()
        sink.assertOnlyError(VoiceErrors.NO_SPEECH, detailContains = "peak=0.0500")
        assertEquals(0, store.index)
    }

    @Test
    fun micFailureIsUnavailable() = core(recorder = { FakeRecorder(RecordResult.Failed("held")) })
        .listenOnce()
        .assertOnlyError(VoiceErrors.MIC_UNAVAILABLE, detailContains = "held")

    @Test
    fun transcriptionFailureIsReported() = core(engine = { FakeEngine("x") { error("decode") } })
        .listenOnce()
        .assertOnlyError(VoiceErrors.RECOGNIZE_FAILED, detailContains = "decode")

    @Test
    fun secondListenWhileBusyGetsBusy() {
        val later = ManualExecutor()
        val core = core(executor = later)
        val first = core.listenOnce()
        val second = core.listenOnce()
        assertTrue(core.busy)
        second.assertOnlyError(VoiceErrors.BUSY)
        later.runAll()
        assertEquals("result:tăng gió", first.terminals.single())
        assertFalse(core.busy)
    }

    @Test
    fun theNextListenWorksOnceTheFirstHasEnded() {
        val core = core()
        core.listenOnce()
        assertEquals("result:tăng gió", core.listenOnce().terminals.single())
    }

    @Test
    fun cancelBeforeRecordingNeverOpensTheMic() {
        val later = ManualExecutor()
        val core = core(executor = later)
        val sink = core.listenOnce()
        core.cancel()
        later.runAll()
        sink.assertOnlyError(VoiceErrors.CANCELLED)
        assertEquals(0, recorderFactoryCalls)
    }

    @Test
    fun cancelWhileRecordingEndsCancelled() {
        lateinit var core: EngineCore
        val recorder = object : UtteranceRecorder {
            override val peak = 0.05f
            var stopped = false
            override fun record(onLevel: (Float) -> Unit, onSpeechStart: () -> Unit, onPause: (FloatArray) -> Unit): RecordResult {
                core.cancel()
                return if (stopped) RecordResult.Cancelled else audio
            }
            override fun cancel() {
                stopped = true
            }
        }
        core = core(recorder = { recorder })
        core.listenOnce().assertOnlyError(VoiceErrors.CANCELLED)
    }

    @Test
    fun exactlyOneTerminalEvenIfCancelRacesTheResult() {
        lateinit var core: EngineCore
        core = core(engine = { FakeEngine("x") { core.cancel() } })
        core.listenOnce().assertOnlyError(VoiceErrors.CANCELLED)
    }

    @Test
    fun cancelWithNothingRunningIsHarmless() {
        val core = core()
        core.cancel()
        assertEquals("result:tăng gió", core.listenOnce().terminals.single())
    }

    @Test
    fun theEngineIsReusedBetweenListens() {
        val core = core()
        core.listenOnce()
        core.listenOnce()
        assertEquals(1, engines.size)
    }

    @Test
    fun idleReleaseFreesTheEngine() {
        val core = core()
        core.listenOnce()
        core.releaseIfIdle()
        assertTrue(engines.single().released)
        core.listenOnce()
        assertEquals(2, engines.size)
    }

    private class SizeEngine : SpeechEngine {
        val decoded = mutableListOf<Int>()
        override fun transcribe(samples: FloatArray): String {
            decoded += samples.size
            return "snap${samples.size}"
        }
        override fun release() = Unit
    }

    private fun sentence(settled: Boolean, size: Int = 2) = RecordResult.Audio(FloatArray(size), settledOnPause = settled)

    @Test
    fun usesTheSnapshotResultWhenTheSpeechEndedInSilence() {
        val decode = ManualExecutor()
        val engine = SizeEngine()
        val recorder = FakeRecorder(sentence(true), pauseSnapshots = listOf(FloatArray(1)), during = decode::runAll)
        val sink = core(recorder = { recorder }, engine = { engine }, decodeExecutor = decode).listenOnce()
        assertEquals("result:snap1", sink.terminals.single())
        assertEquals(listOf(1), engine.decoded)
    }

    @Test
    fun theLatestSnapshotWinsAfterSpeechResumes() {
        val decode = ManualExecutor()
        val engine = SizeEngine()
        val recorder = FakeRecorder(sentence(true, 5), pauseSnapshots = listOf(FloatArray(1), FloatArray(3)), during = decode::runAll)
        val sink = core(recorder = { recorder }, engine = { engine }, decodeExecutor = decode).listenOnce()
        assertEquals("result:snap3", sink.terminals.single())
    }

    @Test
    fun theWholeUtteranceIsDecodedWhenTheTimeLimitCutIt() {
        val decode = ManualExecutor()
        val engine = SizeEngine()
        val recorder = FakeRecorder(sentence(false, 5), pauseSnapshots = listOf(FloatArray(1)), during = decode::runAll)
        val sink = core(recorder = { recorder }, engine = { engine }, decodeExecutor = decode).listenOnce()
        assertEquals("result:snap5", sink.terminals.single())
    }

    @Test
    fun theMicOpensBeforeTheModelIsLoaded() {
        val decode = ManualExecutor()
        var loadedWhenRecording = -1
        val recorder = FakeRecorder(audio, during = {
            loadedWhenRecording = engines.size
            decode.runAll()
        })
        val sink = core(recorder = { recorder }, decodeExecutor = decode).listenOnce()
        assertEquals(0, loadedWhenRecording)
        assertEquals("result:tăng gió", sink.terminals.single())
    }

    @Test
    fun aLoadFailureWhileRecordingCancelsTheMicAndReportsOnce() {
        val decode = ManualExecutor()
        val recorder = FakeRecorder(audio, during = decode::runAll)
        val sink = core(recorder = { recorder }, engine = { error("bad onnx") }, decodeExecutor = decode).listenOnce()
        sink.assertOnlyError(VoiceErrors.MODEL_LOAD_FAILED, detailContains = "bad onnx")
        assertTrue(recorder.cancelled)
    }

    @Test
    fun cancelDuringADecodeAheadEndsOnceWithCancelled() {
        val decode = ManualExecutor()
        lateinit var core: EngineCore
        val recorder = FakeRecorder(sentence(true), pauseSnapshots = listOf(FloatArray(1)), during = { core.cancel() })
        core = core(recorder = { recorder }, decodeExecutor = decode)
        val sink = core.listenOnce()
        decode.runAll()
        sink.assertOnlyError(VoiceErrors.CANCELLED)
    }

    @Test
    fun warmUpLoadsTheEngineOnTheDecodeExecutorAndTheListenReusesIt() {
        val decode = ManualExecutor()
        val core = core(recorder = { FakeRecorder(audio, during = decode::runAll) }, decodeExecutor = decode)
        core.warmUp("vi")
        assertEquals(0, engines.size)
        decode.runAll()
        assertEquals(1, engines.size)
        core.listenOnce()
        assertEquals(1, engines.size)
    }

    @Test
    fun warmUpDoesNothingWhileListening() {
        val decode = ManualExecutor()
        val core = core(executor = ManualExecutor(), decodeExecutor = decode)
        core.listenOnce()
        core.warmUp("vi")
        assertEquals(0, decode.queued)
    }

    @Test
    fun releaseRunsOnTheDecodeExecutor() {
        val decode = ManualExecutor()
        val core = core(decodeExecutor = decode)
        core.warmUp("vi")
        decode.runAll()
        core.releaseIfIdle()
        assertFalse(engines.single().released)
        decode.runAll()
        assertTrue(engines.single().released)
    }

    @Test
    fun aSlowLoadThatFailsAfterRecordingIsALoadFailureNotNoSpeech() {
        val decode = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val recorder = FakeRecorder(sentence(true), pauseSnapshots = listOf(FloatArray(1)))
            val sink = core(
                recorder = { recorder },
                engine = {
                    Thread.sleep(200)
                    error("bad onnx")
                },
                decodeExecutor = decode
            ).listenOnce()
            sink.assertOnlyError(VoiceErrors.MODEL_LOAD_FAILED)
            assertEquals(0, store.index)
        } finally {
            decode.shutdownNow()
        }
    }

    @Test
    fun warmUpQueuedBeforeAListenDoesNotLoadOnceTheListenHasStarted() {
        val decode = ManualExecutor()
        val core = core(executor = ManualExecutor(), decodeExecutor = decode)
        core.warmUp("vi")
        core.listenOnce()
        decode.runAll()
        assertEquals(0, engines.size)
    }

    @Test
    fun anErrorWhileLoadingEndsTheListenAndFreesTheCore() {
        val core = core(engine = { throw OutOfMemoryError("heap") })
        core.listenOnce().assertOnlyError(VoiceErrors.MODEL_LOAD_FAILED)
        assertFalse(core.busy)
    }
}
