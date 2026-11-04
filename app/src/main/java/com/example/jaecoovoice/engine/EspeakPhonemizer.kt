package com.example.jaecoovoice.engine

import android.util.Log
import java.io.File

/**
 * Text to espeak-ng clauses for Piper models, using the espeak-ng-data folder [dataDir].
 *
 * espeak-ng has one global state per process, so every call goes through one process-wide lock;
 * espeak-ng is initialised on first use and again when another [dataDir] is asked for.
 */
class EspeakPhonemizer(private val dataDir: File) {

    /** The raw clauses, or null when espeak-ng cannot start or does not know [voice]. */
    fun clauses(text: String, voice: String): List<EspeakClause>? = synchronized(LOCK) {
        if (text.isBlank()) return emptyList()
        if (!ensureInit()) return null
        val lines = try {
            EspeakNative.nativeClauses(text, voice)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libjaecoo-espeak missing", e)
            return null
        }
        if (lines.isEmpty()) {
            Log.w(TAG, "espeak-ng has no voice '$voice' in $dataDir")
            return null
        }
        lines.map { EspeakClause.parse(it) }
    }

    private fun ensureInit(): Boolean {
        val path = dataDir.absolutePath
        when {
            !dataDir.isDirectory -> Log.w(TAG, "espeak-ng-data missing: $path")
            initialisedDir != path -> initialise(path)
        }
        return dataDir.isDirectory && initialisedDir == path
    }

    private fun initialise(path: String) {
        try {
            if (initialisedDir != null) EspeakNative.nativeTerminate()
            initialisedDir = null
            val rate = EspeakNative.nativeInit(path)
            if (rate > 0) initialisedDir = path else Log.w(TAG, "espeak_Initialize failed ($rate) for $path")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libjaecoo-espeak missing", e)
        }
    }

    private companion object {
        const val TAG = "EspeakPhonemizer"
        val LOCK = Any()

        /** espeak-ng-data folder espeak-ng is initialised with; guarded by [LOCK]. */
        var initialisedDir: String? = null
    }
}
