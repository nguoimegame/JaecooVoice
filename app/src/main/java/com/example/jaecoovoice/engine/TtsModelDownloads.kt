package com.example.jaecoovoice.engine

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Manages background downloading of TTS models.
 * Deduplicates multiple requests for the same model.
 */
class TtsModelDownloads(
    private val executor: Executor,
    private val install: (TtsModel, (Int) -> Unit) -> TtsModelStore.InstallResult
) {
    private val running = mutableMapOf<String, MutableList<ModelListener>>()

    fun isDownloading(language: String): Boolean {
        val id = TtsCatalog.forLanguage(language)?.id ?: return false
        return synchronized(running) { id in running }
    }

    fun start(model: TtsModel, listener: ModelListener) {
        val first = synchronized(running) {
            val listeners = running[model.id]
            if (listeners != null) {
                listeners += listener
                false
            } else {
                running[model.id] = mutableListOf(listener)
                true
            }
        }
        if (first) executor.execute { run(model) }
    }

    private fun listeners(id: String): List<ModelListener> = synchronized(running) { running[id].orEmpty().toList() }

    @Suppress("TooGenericExceptionCaught")
    private fun run(model: TtsModel) {
        var finishedListeners: List<ModelListener> = emptyList()
        var result: TtsModelStore.InstallResult? = null
        try {
            result = install(model) { percent ->
                listeners(model.id).forEach { it.onProgress(percent) }
            }
        } catch (e: Exception) {
            result = TtsModelStore.InstallResult.Failed(e.message ?: "Lỗi tải mô hình TTS")
        } finally {
            finishedListeners = synchronized(running) { running.remove(model.id).orEmpty() }
        }
        when (result) {
            TtsModelStore.InstallResult.Success -> finishedListeners.forEach { it.onDone() }
            is TtsModelStore.InstallResult.Failed -> finishedListeners.forEach { it.onError(result.reason) }
        }
    }

    companion object {
        @Volatile
        private var instance: TtsModelDownloads? = null

        fun get(context: Context): TtsModelDownloads = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context) = TtsModelDownloads(
            Executors.newSingleThreadExecutor { Thread(it, "tts-model-download") }
        ) { model, onProgress ->
            TtsModelStore.install(app.filesDir, model, onProgress)
        }
    }
}
