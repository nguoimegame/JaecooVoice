package com.example.jaecoovoice.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/** Result of one recording. */
sealed class RecordResult {
    class Audio(val samples: FloatArray) : RecordResult()
    object NoSpeech : RecordResult()
    object Cancelled : RecordResult()

    /** The microphone could not open, or gave only zeros ([silent] = true: probably blocked). */
    data class Failed(val reason: String, val silent: Boolean = false) : RecordResult()
}

/** Records one utterance. Blocks the calling thread until it is over. */
interface UtteranceRecorder {
    /** Loudest frame RMS (0–1) of the last recording, to tell a dead mic from a quiet speaker. */
    val peak: Float

    /** The actual [MediaRecorder.AudioSource] used during the recording. */
    val activeSource: Int get() = 0

    fun record(onLevel: (Float) -> Unit = {}, onSpeechStart: () -> Unit = {}): RecordResult

    fun cancel()
}

/**
 * Records 16 kHz mono 16-bit with [AudioRecord] and stops at the end of the utterance
 * ([EndpointDetector]). [source] is a [MediaRecorder.AudioSource] from [MicSources].
 *
 * Needs RECORD_AUDIO (the caller checks). If [source] is held by another app or cannot be
 * initialized, fallback candidates are attempted before failing. If the first [SILENT_FRAMES]
 * frames are all zeros it reports [RecordResult.Failed.silent]: Android 11 hands an app that may
 * not record a muted mic.
 */
class AudioCapture(
    private val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val candidates: List<Int> = MicSources.candidates(source)
) : UtteranceRecorder {

    @Volatile
    private var cancelled = false

    @Volatile
    override var peak = 0f
        private set

    @Volatile
    override var activeSource: Int = source
        private set

    override fun cancel() {
        cancelled = true
    }

    private sealed class Opened {
        class Ready(val recorder: AudioRecord, val source: Int) : Opened()
        class Failed(val reason: String) : Opened()
    }

    override fun record(onLevel: (Float) -> Unit, onSpeechStart: () -> Unit): RecordResult {
        peak = 0f
        if (cancelled) return RecordResult.Cancelled
        return when (val opened = open()) {
            is Opened.Failed -> RecordResult.Failed(opened.reason)
            is Opened.Ready -> try {
                activeSource = opened.source
                capture(opened.recorder, onLevel, onSpeechStart)
            } finally {
                opened.recorder.release()
            }
        }
    }

    private fun open(): Opened {
        val frameSamples = SAMPLE_RATE * FRAME_MS / MS_PER_SECOND
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, frameSamples * 2 * BUFFER_FRAMES)
        var result: Opened = Opened.Failed("microphone held by another app")
        for (cand in candidates) {
            val recorder = tryOpenCandidate(cand, bufferSize)
            if (recorder != null) {
                if (cand != source) Log.i(TAG, "Primary audio source $source unavailable; fell back to $cand")
                result = Opened.Ready(recorder, cand)
                break
            }
        }
        return result
    }

    private fun capture(recorder: AudioRecord, onLevel: (Float) -> Unit, onSpeechStart: () -> Unit): RecordResult {
        val frameSamples = SAMPLE_RATE * FRAME_MS / MS_PER_SECOND
        val detector = EndpointDetector(frameMs = FRAME_MS)
        val frame = ShortArray(frameSamples)
        val all = FloatArrayBuilder()
        var silentFrames = 0
        var speaking = false
        var result: RecordResult? = null
        while (result == null && !cancelled) {
            val read = readFrame(recorder, frame)
            val rms = rms(frame, read.coerceAtLeast(0))
            onLevel(rms)
            if (rms > peak) peak = rms
            all.add(frame, read.coerceAtLeast(0))
            silentFrames = if (read > 0 && frame.take(read).all { it.toInt() == 0 }) silentFrames + 1 else 0
            val dead = silentFrames >= SILENT_FRAMES && all.size <= SILENT_FRAMES * frameSamples
            result = verdict(read, dead, detector, rms, all)
            if (!speaking && detector.speechStarted) {
                speaking = true
                onSpeechStart()
            }
        }
        return result ?: RecordResult.Cancelled
    }

    /** What one frame decides: null to keep recording. */
    private fun verdict(read: Int, dead: Boolean, detector: EndpointDetector, rms: Float, all: FloatArrayBuilder): RecordResult? = when {
        read < 0 -> RecordResult.Failed("AudioRecord.read error $read")
        dead -> RecordResult.Failed("microphone gives only zeros", silent = true)
        else -> when (detector.onFrame(rms)) {
            EndpointDetector.Signal.END -> RecordResult.Audio(normalize(all.toArray()))
            EndpointDetector.Signal.NO_SPEECH -> RecordResult.NoSpeech
            EndpointDetector.Signal.CONTINUE -> null
        }
    }

    /** Car microphones are quiet; raise the peak to the level the model is used to. */
    private fun normalize(samples: FloatArray): FloatArray {
        val top = samples.maxOfOrNull { abs(it) } ?: 0f
        if (top < MIN_NORMALIZE_PEAK || top >= TARGET_PEAK) return samples
        val gain = minOf(TARGET_PEAK / top, MAX_GAIN)
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /** Reads one whole frame; returns the samples read or a negative error code. */
    private fun readFrame(recorder: AudioRecord, frame: ShortArray): Int {
        var total = 0
        while (total < frame.size && !cancelled) {
            val n = recorder.read(frame, total, frame.size - total)
            if (n < 0) return n
            total += n
        }
        return total
    }

    private fun rms(frame: ShortArray, count: Int): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i] / PCM_MAX
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    /** A growing float array without boxing. */
    private class FloatArrayBuilder {
        private var data = FloatArray(INITIAL_CAPACITY)
        var size = 0
            private set

        fun add(frame: ShortArray, count: Int) {
            if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
            for (i in 0 until count) data[size + i] = frame[i] / PCM_MAX.toFloat()
            size += count
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 100
        private const val MS_PER_SECOND = 1_000
        private const val BUFFER_FRAMES = 4
        private const val SILENT_FRAMES = 10
        private const val PCM_MAX = 32768.0
        private const val MIN_NORMALIZE_PEAK = 0.002f
        private const val TARGET_PEAK = 0.5f
        private const val MAX_GAIN = 50f
        private const val INITIAL_CAPACITY = SAMPLE_RATE * 2
    }
}

private fun tryOpenCandidate(cand: Int, bufferSize: Int): AudioRecord? {
    val recorder = tryCreateRecorder(cand, bufferSize) ?: return null
    return if (startActiveRecording(recorder, cand)) recorder else null
}

@SuppressLint("MissingPermission")
private fun tryCreateRecorder(cand: Int, bufferSize: Int): AudioRecord? = try {
    AudioRecord(cand, AudioCapture.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
} catch (e: SecurityException) {
    Log.w("AudioCapture", "RECORD_AUDIO denied for source $cand", e)
    null
} catch (e: IllegalArgumentException) {
    Log.w("AudioCapture", "Source $cand not supported on this device", e)
    null
}

private fun startActiveRecording(recorder: AudioRecord, cand: Int): Boolean {
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        return false
    }
    val ok = try {
        recorder.startRecording()
        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (e: IllegalStateException) {
        Log.w("AudioCapture", "Cannot startRecording for source $cand", e)
        false
    }
    if (!ok) recorder.release()
    return ok
}
