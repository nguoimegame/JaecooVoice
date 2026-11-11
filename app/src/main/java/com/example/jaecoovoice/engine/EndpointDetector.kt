package com.example.jaecoovoice.engine

/**
 * Decides when an utterance is over from each frame's loudness (RMS 0–1). Energy only, not a real
 * VAD: enough for a short command after one button press, and it needs no extra model.
 *
 * Speech threshold = [MIN_THRESHOLD] or [NOISE_FACTOR] times the noise floor (the quietest of the
 * first [CALIBRATION_FRAMES] frames), whichever is higher: a moving car is much noisier.
 */
class EndpointDetector(
    private val frameMs: Int = 100,
    private val silenceMs: Int = 600,
    private val maxMs: Int = 7_000,
    private val noSpeechMs: Int = 4_000,
    private val speculateMs: Int = 300
) {
    /** [PAUSE] is sent once per silence, [speculateMs] after speech: time to decode ahead of [END]. */
    enum class Signal { CONTINUE, PAUSE, END, NO_SPEECH }

    private var elapsedMs = 0
    private var frames = 0
    private var noiseFloor = Float.MAX_VALUE
    private var silentMs = 0
    private var pauseSent = false

    /** True from the first frame loud enough to be speech. */
    var speechStarted = false
        private set

    /** After [Signal.END]: true when it came from silence, false when the time limit cut the speech. */
    var endedBySilence = false
        private set

    fun onFrame(rms: Float): Signal {
        frames++
        elapsedMs += frameMs
        if (frames <= CALIBRATION_FRAMES) noiseFloor = minOf(noiseFloor, rms, MAX_NOISE_FLOOR)

        val speaking = rms > maxOf(MIN_THRESHOLD, noiseFloor * NOISE_FACTOR)
        if (speaking) {
            speechStarted = true
            silentMs = 0
            pauseSent = false
        } else if (speechStarted) {
            silentMs += frameMs
        }

        val timedOut = elapsedMs >= maxMs
        val silent = silentMs >= silenceMs
        return when {
            speechStarted && (silent || timedOut) -> {
                endedBySilence = silent && !timedOut
                Signal.END
            }
            !speechStarted && elapsedMs >= noSpeechMs -> Signal.NO_SPEECH
            speechStarted && silentMs >= speculateMs && !pauseSent -> {
                pauseSent = true
                Signal.PAUSE
            }
            else -> Signal.CONTINUE
        }
    }

    companion object {
        const val CALIBRATION_FRAMES = 3
        const val MIN_THRESHOLD = 0.006f
        const val NOISE_FACTOR = 3f

        /** Ceiling of the noise floor: someone talking right at the press must not lift the threshold above their own voice. */
        const val MAX_NOISE_FLOOR = 0.05f
    }
}
