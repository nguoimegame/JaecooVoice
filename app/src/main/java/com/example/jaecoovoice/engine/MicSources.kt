package com.example.jaecoovoice.engine

import android.content.Context
import android.media.MediaRecorder

/**
 * The microphone sources tried in turn. On the head unit iFlytek can hold one source while
 * another still works, so a source that only gave silence is swapped for the next one.
 */
object MicSources {

    val SOURCES = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
        MediaRecorder.AudioSource.MIC to "MIC",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
        MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER"
    )

    /** Below this the microphone gave no real signal. */
    const val DEAD_PEAK = 0.002f

    /** The source to use next, after a listen on [current] whose loudest frame was [peak]. */
    fun next(current: Int, peak: Float): Int {
        val index = current.mod(SOURCES.size)
        return if (peak < DEAD_PEAK) (index + 1) % SOURCES.size else index
    }

    fun source(index: Int): Int = SOURCES[index.mod(SOURCES.size)].first

    fun name(index: Int): String = SOURCES[index.mod(SOURCES.size)].second

    /** All sources with [preferredSource] tried first, followed by remaining sources in fallback order. */
    fun candidates(preferredSource: Int): List<Int> {
        val preferred = SOURCES.map { it.first }.firstOrNull { it == preferredSource }
            ?: MediaRecorder.AudioSource.VOICE_RECOGNITION
        val others = SOURCES.map { it.first }.filter { it != preferred }
        return listOf(preferred) + others
    }

    /** 0-based index in [SOURCES] matching [source], or 0 if unknown. */
    fun indexOfSource(source: Int): Int {
        val idx = SOURCES.indexOfFirst { it.first == source }
        return if (idx >= 0) idx else 0
    }
}

/** Where the current source index is kept between listens. */
interface MicSourceStore {
    fun get(): Int
    fun set(index: Int)
}

class PrefsMicSourceStore(context: Context) : MicSourceStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): Int = prefs.getInt(KEY, 0)

    override fun set(index: Int) {
        prefs.edit().putInt(KEY, index).apply()
    }

    private companion object {
        const val PREFS = "engine"
        const val KEY = "mic_source"
    }
}
