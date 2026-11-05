package com.example.jaecoovoice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.example.jaecoovoice.engine.CallerCheck
import com.example.jaecoovoice.engine.CrashTrace
import com.example.jaecoovoice.engine.EngineCore
import com.example.jaecoovoice.engine.IListenCallback
import com.example.jaecoovoice.engine.IModelCallback
import com.example.jaecoovoice.engine.ITtsCallback
import com.example.jaecoovoice.engine.IVoiceEngine
import com.example.jaecoovoice.engine.ListenSink
import com.example.jaecoovoice.engine.ModelCatalog
import com.example.jaecoovoice.engine.ModelDownloads
import com.example.jaecoovoice.engine.ModelListener
import com.example.jaecoovoice.engine.ModelStore
import com.example.jaecoovoice.engine.StatusKeys
import com.example.jaecoovoice.engine.TtsCatalog
import com.example.jaecoovoice.engine.TtsCore
import com.example.jaecoovoice.engine.TtsModel
import com.example.jaecoovoice.engine.TtsModelDownloads
import com.example.jaecoovoice.engine.TtsModelStore
import com.example.jaecoovoice.engine.TtsPlayer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves [IVoiceEngine] to JaecooApp (docs/jaecoovoice-contract.md). Bound only, never started:
 * the process lives while JaecooApp's foreground VoiceService holds the binding, and that
 * binding (BIND_INCLUDE_CAPABILITIES) is also what lets this process use the microphone.
 *
 * Declared in the manifest and named by JaecooApp, so it stays in the root package.
 */
class VoiceEngineService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val core by lazy { EngineCore.get(this) }
    private val downloads by lazy { ModelDownloads.get(this) }
    private val ttsDownloads by lazy { TtsModelDownloads.get(this) }
    private val ttsPlayer by lazy { TtsPlayer.create(this) }
    private val ttsCore by lazy { TtsCore.get(this) }
    private val ttsExecutor = Executors.newSingleThreadExecutor { Thread(it, "tts-engine") }
    private val speechGeneration = AtomicLong(0L)
    private val activeSpeakingGeneration = AtomicLong(0L)

    private val releaseEngine = Runnable { core.releaseIfIdle() }
    private val releaseTtsEngine = Runnable { ttsCore.releaseIfIdle() }

    @Suppress("TooManyFunctions")
    private val binder = object : IVoiceEngine.Stub() {

        override fun apiVersion(): Int {
            enforceCaller()
            return API_VERSION
        }

        override fun languages(): List<String> {
            enforceCaller()
            return ModelCatalog.languages()
        }

        override fun modelStatus(language: String?): Bundle {
            enforceCaller()
            val lang = language.orEmpty()
            val model = ModelCatalog.forLanguage(lang)
            val status = StatusKeys.status(
                language = lang,
                model = model,
                installed = model != null && ModelStore.isInstalled(filesDir, model),
                downloading = downloads.isDownloading(lang),
                loadError = core.loadError(lang)
            )
            return Bundle().apply {
                for ((key, value) in status) {
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                    }
                }
            }
        }

        override fun downloadModel(language: String?, cb: IModelCallback?) {
            enforceCaller()
            val model = ModelCatalog.forLanguage(language.orEmpty())
            if (model == null) {
                safely { cb?.onError("Không có mô hình cho ${language.orEmpty()}") }
                return
            }
            downloads.start(model, RemoteModelListener(cb))
        }

        override fun deleteModel(language: String?) {
            enforceCaller()
            val model = ModelCatalog.forLanguage(language.orEmpty()) ?: return
            core.releaseIfIdle()
            ModelStore.delete(filesDir, model)
        }

        override fun listen(language: String?, cb: IListenCallback?) {
            enforceCaller()
            if (cb == null) return
            speechGeneration.incrementAndGet()
            activeSpeakingGeneration.set(0L)
            ttsPlayer.stop()
            core.listen(language.orEmpty(), RemoteListenSink(cb))
        }

        override fun cancel() {
            enforceCaller()
            speechGeneration.incrementAndGet()
            activeSpeakingGeneration.set(0L)
            ttsPlayer.stop()
            core.cancel()
        }

        override fun ttsModelStatus(language: String?): Bundle {
            enforceCaller()
            val lang = language.orEmpty()
            val model = TtsCatalog.forLanguage(lang)
            val installed = model != null && TtsModelStore.isInstalled(filesDir, model)
            val downloading = ttsDownloads.isDownloading(lang)
            val label = model?.label.orEmpty()
            return Bundle().apply {
                putBoolean("tts_installed", installed)
                putBoolean("tts_downloading", downloading)
                putString("tts_label", label)
            }
        }

        override fun downloadTtsModel(language: String?, cb: IModelCallback?) {
            enforceCaller()
            val lang = language.orEmpty()
            val model = TtsCatalog.forLanguage(lang)
            if (model == null) {
                safely { cb?.onError("Không có mô hình TTS cho $lang") }
                return
            }
            ttsDownloads.start(model, RemoteModelListener(cb))
        }

        override fun deleteTtsModel(language: String?) {
            enforceCaller()
            val lang = language.orEmpty()
            val model = TtsCatalog.forLanguage(lang) ?: return
            speechGeneration.incrementAndGet()
            activeSpeakingGeneration.set(0L)
            ttsPlayer.stop()
            ttsCore.release()
            TtsModelStore.delete(filesDir, model)
        }

        override fun speak(text: String?, language: String?, cb: ITtsCallback?) {
            enforceCaller()
            val txt = text.orEmpty()
            val lang = language.orEmpty()
            val model = TtsCatalog.forLanguage(lang)
            // A previous native crash leaves no trace but this; tell JaecooApp (it logs and shows it).
            CrashTrace.takeReport(this@VoiceEngineService)?.let { speakFailed(cb, it) }
            when {
                txt.isBlank() -> safely { cb?.onDone() }
                model == null || !TtsModelStore.isInstalled(filesDir, model) -> speakFailed(cb, "tts model not installed: $lang")
                else -> {
                    val genId = speechGeneration.incrementAndGet()
                    activeSpeakingGeneration.set(genId)
                    ttsPlayer.stop()
                    executeSpeak(txt, lang, cb, genId)
                }
            }
        }

        override fun stopSpeaking() {
            enforceCaller()
            speechGeneration.incrementAndGet()
            activeSpeakingGeneration.set(0L)
            ttsPlayer.stop()
        }

        override fun isSpeaking(): Boolean {
            enforceCaller()
            return activeSpeakingGeneration.get() != 0L || ttsPlayer.isSpeaking
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        speechGeneration.incrementAndGet()
        activeSpeakingGeneration.set(0L)
        main.removeCallbacks(releaseEngine)
        main.removeCallbacks(releaseTtsEngine)
        core.releaseIfIdle()
        ttsPlayer.stop()
        ttsCore.release()
        ttsExecutor.shutdown()
        super.onDestroy()
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (!CallerCheck.isAllowed(packageManager.getPackagesForUid(uid))) {
            throw SecurityException("uid $uid is not ${CallerCheck.JAECOO_PACKAGE}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeSpeak(txt: String, lang: String, cb: ITtsCallback?, genId: Long) {
        main.removeCallbacks(releaseTtsEngine)
        ttsExecutor.execute {
            if (speechGeneration.get() != genId) {
                activeSpeakingGeneration.compareAndSet(genId, 0L)
                return@execute
            }
            try {
                val chunks = mutableListOf<FloatArray>()
                var sampleRate = 0
                val result = ttsCore.synthesize(
                    language = lang,
                    text = txt,
                    isCancelled = { speechGeneration.get() != genId }
                ) { chunk, rate ->
                    chunks.add(chunk)
                    sampleRate = rate
                }

                val totalSamples = chunks.sumOf { it.size }
                when {
                    // Cancelled (stopSpeaking or a newer speak/listen): say nothing more.
                    speechGeneration.get() != genId || result == TtsCore.SynthResult.Cancelled -> return@execute
                    result is TtsCore.SynthResult.Failed -> {
                        speakFailed(cb, result.reason)
                        return@execute
                    }
                    totalSamples == 0 -> {
                        speakFailed(cb, "tts produced no audio: $lang")
                        return@execute
                    }
                }

                val samples = FloatArray(totalSamples)
                var offset = 0
                for (chunk in chunks) {
                    System.arraycopy(chunk, 0, samples, offset, chunk.size)
                    offset += chunk.size
                }

                ttsPlayer.play(
                    samples = samples,
                    sampleRate = sampleRate,
                    isCancelled = { speechGeneration.get() != genId },
                    onStart = { safely { cb?.onStart() } },
                    onDone = {
                        activeSpeakingGeneration.compareAndSet(genId, 0L)
                        safely { cb?.onDone() }
                    },
                    onError = { msg ->
                        activeSpeakingGeneration.compareAndSet(genId, 0L)
                        safely { cb?.onError(msg) }
                    }
                )
            } catch (e: Exception) {
                safely { cb?.onError(e.message ?: "Lỗi phát âm TTS") }
            } finally {
                activeSpeakingGeneration.compareAndSet(genId, 0L)
                scheduleTtsRelease()
            }
        }
    }

    /** Every speak failure is logged here and reaches JaecooApp, which sends it to the log server. */
    private fun speakFailed(cb: ITtsCallback?, reason: String) {
        Log.w(TTS_TAG, reason)
        safely { cb?.onError(reason) }
    }

    /** Engine is freed five minutes after the last listen; another listen pushes that back. */
    private fun scheduleRelease() {
        main.removeCallbacks(releaseEngine)
        main.postDelayed(releaseEngine, ENGINE_IDLE_MS)
    }

    private fun scheduleTtsRelease() {
        main.removeCallbacks(releaseTtsEngine)
        main.postDelayed(releaseTtsEngine, ENGINE_IDLE_MS)
    }

    /** Forwards to JaecooApp; if it is gone, the listen is cancelled. */
    private inner class RemoteListenSink(private val cb: IListenCallback) : ListenSink {
        override fun onState(state: Int) = forward { cb.onState(state) }
        override fun onLevel(rms: Float) = forward { cb.onLevel(rms) }
        override fun onResult(text: String) {
            forward { cb.onResult(text) }
            scheduleRelease()
        }
        override fun onError(code: Int, detail: String) {
            forward { cb.onError(code, detail) }
            scheduleRelease()
        }

        private fun forward(call: () -> Unit) {
            if (!safely(call)) core.cancel()
        }
    }

    private class RemoteModelListener(private val cb: IModelCallback?) : ModelListener {
        override fun onProgress(percent: Int) {
            safely { cb?.onProgress(percent) }
        }
        override fun onDone() {
            safely { cb?.onDone() }
        }
        override fun onError(message: String) {
            safely { cb?.onError(message) }
        }
    }

    private companion object {
        const val TAG = "VoiceEngineService"
        const val TTS_TAG = "SherpaTtsEngine"
        const val API_VERSION = 2
        const val ENGINE_IDLE_MS = 5 * 60 * 1_000L

        /** False when the other side is gone. */
        fun safely(call: () -> Unit): Boolean = try {
            call()
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "JaecooApp is gone", e)
            false
        }
    }
}
