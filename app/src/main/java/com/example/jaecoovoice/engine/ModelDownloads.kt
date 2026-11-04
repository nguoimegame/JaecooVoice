package com.example.jaecoovoice.engine

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Hears how one model download goes. Called on the download thread. */
interface ModelListener {
    fun onProgress(percent: Int)
    fun onDone()
    fun onError(message: String)
}

/**
 * At most one download per model (a model shared by several languages counts once). Starting a
 * model that is already downloading only adds the listener, so a tab that is closed and opened again follows the same download instead of
 * starting a second one into the same staging folder.
 */
class ModelDownloads(
    private val executor: Executor,
    private val install: (VoiceModel, (Int) -> Unit) -> ModelStore.InstallResult
) {
    private val running = mutableMapOf<String, MutableList<ModelListener>>()

    fun isDownloading(language: String): Boolean {
        val id = ModelCatalog.forLanguage(language)?.id ?: return false
        return synchronized(running) { id in running }
    }

    fun start(model: VoiceModel, listener: ModelListener) {
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

    private fun run(model: VoiceModel) {
        val result = install(model) { percent -> listeners(model.id).forEach { it.onProgress(percent) } }
        val finished = synchronized(running) { running.remove(model.id).orEmpty() }
        when (result) {
            ModelStore.InstallResult.Success -> finished.forEach { it.onDone() }
            is ModelStore.InstallResult.Failed -> finished.forEach { it.onError(result.reason) }
        }
    }

    companion object {
        @Volatile
        private var instance: ModelDownloads? = null

        /** One per process, so a download outlives the service binding that started it. */
        fun get(context: Context): ModelDownloads = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context) = ModelDownloads(Executors.newSingleThreadExecutor { Thread(it, "model-download") }) { model, onProgress ->
            ModelStore.install(app.filesDir, model, onProgress)
        }
    }
}
