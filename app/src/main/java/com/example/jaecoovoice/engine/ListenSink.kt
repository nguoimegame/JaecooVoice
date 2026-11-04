package com.example.jaecoovoice.engine

/**
 * What one listen reports, on the engine thread. A listen ends with exactly one [onResult] or
 * [onError]; nothing follows it.
 */
interface ListenSink {
    /** The microphone is open. */
    fun onReady() {}

    /** The endpoint detector heard the first speech frame. */
    fun onSpeechStart() {}

    /** Recording stopped (the utterance ended, or it timed out or failed). */
    fun onEnd() {}

    /** [STATE_LISTENING] or [STATE_RECOGNIZING]. */
    fun onState(state: Int)

    /** Frame loudness, RMS 0–1, every 100 ms while listening. */
    fun onLevel(rms: Float)

    /** Trimmed, never blank. */
    fun onResult(text: String)

    /** A code from [VoiceErrors]; [detail] is for logs only. */
    fun onError(code: Int, detail: String)

    companion object {
        const val STATE_LISTENING = 0
        const val STATE_RECOGNIZING = 1
    }
}
