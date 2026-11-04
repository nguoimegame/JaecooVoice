package com.example.jaecoovoice.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one recognizer of this process: record an utterance, transcribe it. VoiceEngineService
 * (JaecooApp), JaecooRecognitionService and RecognizeSpeechActivity (other apps) all go through
 * here, so there is at most one listen at a time; a second one gets [VoiceErrors.BUSY].
 *
 * Work runs on [executor] (one thread). The engine loads lazily, is kept for one language at a
 * time and is freed by [releaseIfIdle].
 */
@Suppress("TooGenericExceptionCaught")
class EngineCore(
    private val executor: Executor,
    private val modelFor: (String) -> VoiceModel?,
    private val installedDir: (VoiceModel) -> File?,
    engineFactory: (VoiceModel, File) -> SpeechEngine,
    private val mic: MicSetup
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
    }

    private val lock = Any()
    private var current: Session? = null

    /** Only touched on [executor]. */
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
        executor.execute {
            if (!busy) slot.release()
        }
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
                val speech = slot.get(language, model, dir)
                when {
                    speech == null -> fail(session, VoiceErrors.MODEL_LOAD_FAILED, slot.loadError(language).orEmpty())
                    session.cancelled -> fail(session, VoiceErrors.CANCELLED)
                    else -> record(session, speech)
                }
            }
        }
    }

    private fun record(session: Session, speech: SpeechEngine) {
        val sink = session.sink
        val sourceIndex = mic.sources.get()
        val recorder = mic.recorderFactory(MicSources.source(sourceIndex))
        session.recorder = recorder
        if (session.cancelled) recorder.cancel()
        sink.onReady()
        sink.onState(ListenSink.STATE_LISTENING)
        val recorded = recorder.record(onLevel = sink::onLevel, onSpeechStart = sink::onSpeechStart)
        sink.onEnd()
        val effectiveSourceIndex = if (recorder.activeSource != 0) {
            MicSources.indexOfSource(recorder.activeSource)
        } else {
            sourceIndex
        }
        val capture = Capture(effectiveSourceIndex, recorder.peak)
        when (recorded) {
            is RecordResult.Audio -> {
                if (effectiveSourceIndex != sourceIndex) {
                    mic.sources.set(effectiveSourceIndex)
                }
                transcribe(session, speech, recorded.samples, capture)
            }
            RecordResult.NoSpeech -> noSpeech(session, VoiceErrors.NO_SPEECH, capture)
            RecordResult.Cancelled -> fail(session, VoiceErrors.CANCELLED)
            is RecordResult.Failed -> {
                mic.sources.set(MicSources.next(sourceIndex, 0f))
                if (recorded.silent) {
                    noSpeech(session, VoiceErrors.MIC_SILENT, capture)
                } else {
                    fail(session, VoiceErrors.MIC_UNAVAILABLE, recorded.reason)
                }
            }
        }
    }

    private fun transcribe(session: Session, speech: SpeechEngine, samples: FloatArray, capture: Capture) {
        session.sink.onState(ListenSink.STATE_RECOGNIZING)
        val text = try {
            speech.transcribe(samples).trim()
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
