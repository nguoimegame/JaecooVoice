package com.example.jaecoovoice.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one recognizer of this process: record an utterance, transcribe it. VoiceEngineService
 * (JaecooApp), JaecooRecognitionService and RecognizeSpeechActivity (other apps) all go through
 * here, so there is at most one listen at a time; a second one gets [VoiceErrors.BUSY].
 *
 * Recording runs on [executor] (one thread, `voice-engine`). Loading, decoding ahead and freeing
 * the engine run on [decodeExecutor] (one thread, `voice-decode`): the microphone opens while the
 * model still loads, and a sentence is decoded from the pause snapshot before its end is certain.
 * The engine loads lazily, is kept for one language at a time and is freed by [releaseIfIdle].
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList", "TooManyFunctions")
class EngineCore(
    private val executor: Executor,
    private val decodeExecutor: Executor,
    private val modelFor: (String) -> VoiceModel?,
    private val installedDir: (VoiceModel) -> File?,
    engineFactory: (VoiceModel, File) -> SpeechEngine,
    private val mic: MicSetup,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** How to reach the microphone: permission, a recorder per source, and the current source. */
    class MicSetup(
        val sources: MicSourceStore,
        val hasPermission: () -> Boolean,
        val recorderFactory: (Int) -> UtteranceRecorder
    )

    /** One recording's source and loudest frame, for rotation and the log detail. */
    private class Capture(val sourceIndex: Int, val peak: Float) {
        val detail get() = "source=%s peak=%.4f".format(Locale.ROOT, MicSources.name(sourceIndex), peak)
    }

    private class Session(val sink: ListenSink) {
        val ended = AtomicBoolean(false)

        @Volatile
        var cancelled = false

        @Volatile
        var recorder: UtteranceRecorder? = null

        /** The model failed to load while the microphone was open. */
        @Volatile
        var loadFailed = false

        /** Decode of the latest pause snapshot: the whole sentence if the speech ended in silence. */
        @Volatile
        var speculative: FutureTask<String>? = null
    }

    private val lock = Any()
    private var current: Session? = null

    /** Only touched on [decodeExecutor]. */
    private val slot = EngineSlot(engineFactory)

    val busy: Boolean get() = synchronized(lock) { current != null }

    /** Why the model of [language] last failed to load, or null. */
    fun loadError(language: String): String? = slot.loadError(language)

    /** Starts one listen; [sink] hears exactly one result or error. */
    fun listen(language: String, sink: ListenSink) {
        val session = Session(sink)
        val accepted = synchronized(lock) {
            if (current != null) false else true.also { current = session }
        }
        if (!accepted) {
            sink.onError(VoiceErrors.BUSY, "")
            return
        }
        executor.execute {
            try {
                run(session, language)
            } catch (e: RuntimeException) {
                Log.e(TAG, "listen failed", e)
                fail(session, VoiceErrors.RECOGNIZE_FAILED, describe(e))
            }
        }
    }

    /** Cancels the running listen, if any. It ends with [VoiceErrors.CANCELLED]. */
    fun cancel() {
        val session = synchronized(lock) { current } ?: return
        session.cancelled = true
        session.recorder?.cancel()
    }

    /** Frees the engine (about 100 MB) when nothing is listening. */
    fun releaseIfIdle() {
        decodeExecutor.execute {
            if (!busy) slot.release()
        }
    }

    /** Loads the engine for [language] ahead of a listen; does nothing while listening or without a model. */
    fun warmUp(language: String) {
        val model = modelFor(language)
        val dir = model?.let(installedDir)
        if (!busy && model != null && dir != null) decodeExecutor.execute { slot.get(language, model, dir) }
    }

    private fun run(session: Session, language: String) {
        val model = modelFor(language)
        val dir = model?.let(installedDir)
        when {
            session.cancelled -> fail(session, VoiceErrors.CANCELLED)
            model == null -> fail(session, VoiceErrors.LANGUAGE_UNSUPPORTED)
            dir == null -> fail(session, VoiceErrors.MODEL_MISSING)
            !mic.hasPermission() -> fail(session, VoiceErrors.NO_MIC_PERMISSION)
            else -> {
                val startedAt = clock()
                val load = FutureTask(
                    Callable {
                        slot.get(language, model, dir).also {
                            if (it == null) {
                                session.loadFailed = true
                                session.recorder?.cancel()
                            }
                        }
                    }
                )
                decodeExecutor.execute(load)
                when {
                    load.isDone && load.get() == null -> failLoad(session, language)
                    session.cancelled -> fail(session, VoiceErrors.CANCELLED)
                    else -> record(session, Listen(language, load, startedAt))
                }
            }
        }
    }

    /** One listen's engine: [load] resolves to it (or null) on the decode thread. */
    private class Listen(val language: String, val load: FutureTask<SpeechEngine?>, val startedAt: Long)

    private class Timing(val loadMs: Long, val recordMs: Long)

    private fun failLoad(session: Session, language: String) = fail(session, VoiceErrors.MODEL_LOAD_FAILED, slot.loadError(language).orEmpty())

    private fun record(session: Session, listen: Listen) {
        val sink = session.sink
        val sourceIndex = mic.sources.get()
        val recorder = mic.recorderFactory(MicSources.source(sourceIndex))
        session.recorder = recorder
        if (session.cancelled || session.loadFailed) recorder.cancel()
        sink.onReady()
        sink.onState(ListenSink.STATE_LISTENING)
        val recordStart = clock()
        val recorded = recorder.record(
            onLevel = sink::onLevel,
            onSpeechStart = sink::onSpeechStart,
            onPause = { snapshot -> session.speculative = decodeAhead(listen.load, snapshot) }
        )
        val timing = Timing(recordStart - listen.startedAt, clock() - recordStart)
        sink.onEnd()
        val effectiveSourceIndex = if (recorder.activeSource != 0) {
            MicSources.indexOfSource(recorder.activeSource)
        } else {
            sourceIndex
        }
        val capture = Capture(effectiveSourceIndex, recorder.peak)
        when {
            session.loadFailed -> failLoad(session, listen.language)
            recorded is RecordResult.Audio -> {
                if (effectiveSourceIndex != sourceIndex) {
                    mic.sources.set(effectiveSourceIndex)
                }
                transcribe(session, listen, recorded, capture, timing)
            }
            recorded == RecordResult.NoSpeech -> noSpeech(session, VoiceErrors.NO_SPEECH, capture)
            recorded == RecordResult.Cancelled -> fail(session, VoiceErrors.CANCELLED)
            recorded is RecordResult.Failed -> {
                mic.sources.set(MicSources.next(sourceIndex, 0f))
                if (recorded.silent) {
                    noSpeech(session, VoiceErrors.MIC_SILENT, capture)
                } else {
                    fail(session, VoiceErrors.MIC_UNAVAILABLE, recorded.reason)
                }
            }
        }
    }

    /** Queues a decode of [snapshot] behind the model load (the decode thread is FIFO). */
    private fun decodeAhead(load: FutureTask<SpeechEngine?>, snapshot: FloatArray): FutureTask<String> {
        val task = FutureTask(Callable { load.get()?.transcribe(snapshot)?.trim().orEmpty() })
        decodeExecutor.execute(task)
        return task
    }

    /** The text decoded ahead when the speech ended in silence, or null: decode it all. */
    private fun decodedAhead(session: Session, recorded: RecordResult.Audio): String? {
        val task = session.speculative?.takeIf { recorded.settledOnPause } ?: return null
        return try {
            task.get()
        } catch (e: ExecutionException) {
            Log.w(TAG, "decode ahead failed", e)
            null
        }
    }

    private fun transcribe(session: Session, listen: Listen, recorded: RecordResult.Audio, capture: Capture, timing: Timing) {
        session.sink.onState(ListenSink.STATE_RECOGNIZING)
        val decodeStart = clock()
        val text = try {
            val ahead = decodedAhead(session, recorded)
            val engine = if (ahead == null) listen.load.get() else null
            if (ahead == null && engine == null) {
                failLoad(session, listen.language)
                return
            }
            val result = ahead ?: engine!!.transcribe(recorded.samples).trim()
            val mode = when {
                ahead != null -> "hit"
                session.speculative != null -> "miss"
                else -> "none"
            }
            Log.i(TAG, "listen load=${timing.loadMs}ms record=${timing.recordMs}ms decode=${clock() - decodeStart}ms speculative=$mode")
            result
        } catch (e: RuntimeException) {
            Log.e(TAG, "transcribe failed", e)
            fail(session, VoiceErrors.RECOGNIZE_FAILED, describe(e))
            return
        }
        when {
            session.cancelled -> fail(session, VoiceErrors.CANCELLED)
            text.isEmpty() -> noSpeech(session, VoiceErrors.NO_SPEECH, capture)
            else -> end(session) { it.onResult(text) }
        }
    }

    /** Nothing usable was heard: a dead source is swapped for the next one before reporting. */
    private fun noSpeech(session: Session, code: Int, capture: Capture) {
        mic.sources.set(MicSources.next(capture.sourceIndex, capture.peak))
        fail(session, code, capture.detail)
    }

    private fun fail(session: Session, code: Int, detail: String = "") = end(session) { it.onError(code, detail) }

    /** Frees the core for the next listen first, so a caller may start one from inside the callback. */
    private fun end(session: Session, deliver: (ListenSink) -> Unit) {
        if (!session.ended.compareAndSet(false, true)) return
        synchronized(lock) { if (current === session) current = null }
        deliver(session.sink)
    }

    companion object {
        private const val TAG = "EngineCore"

        @Volatile
        private var instance: EngineCore? = null

        fun get(context: Context): EngineCore = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): EngineCore = EngineCore(
            executor = Executors.newSingleThreadExecutor { Thread(it, "voice-engine") },
            decodeExecutor = Executors.newSingleThreadExecutor { Thread(it, "voice-decode") },
            modelFor = ModelCatalog::forLanguage,
            installedDir = { model -> ModelStore.dir(app.filesDir, model).takeIf { ModelStore.isInstalled(app.filesDir, model) } },
            engineFactory = { model, dir -> SherpaSpeechEngine(model, dir) },
            mic = MicSetup(
                sources = PrefsMicSourceStore(app),
                hasPermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                recorderFactory = { source -> AudioCapture(source) }
            )
        )
    }
}

internal fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"
