package com.example.jaecoovoice.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Controller for requesting and abandoning audio focus with ducking.
 */
interface AudioFocusController {
    fun requestFocus(): Boolean
    fun abandonFocus()
}

/**
 * Android implementation of [AudioFocusController] requesting
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK on stream USAGE_ASSISTANT.
 */
@Suppress("TooGenericExceptionCaught")
class AndroidAudioFocusController(private val audioManager: AudioManager) : AudioFocusController {
    private val lock = Any()
    private var focusRequest: AudioFocusRequest? = null

    override fun requestFocus(): Boolean {
        synchronized(lock) {
            return try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (e: Exception) {
                Log.w(TAG, "Audio focus request failed", e)
                false
            }
        }
    }

    override fun abandonFocus() {
        synchronized(lock) {
            focusRequest?.let {
                try {
                    audioManager.abandonAudioFocusRequest(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Audio focus abandon failed", e)
                }
                focusRequest = null
            }
        }
    }

    private companion object {
        const val TAG = "AudioFocusController"
    }
}

/**
 * Interface for writing PCM audio to hardware speakers.
 */
interface TrackPlayer {
    fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean)
    fun stop()
}

/**
 * Android [AudioTrack] player. Plays 16-bit PCM with USAGE_ASSISTANT, the way the J7's own iFlytek
 * TTS does (a float track was not heard on the car).
 * A track that cannot start or write throws, so the caller hears about it.
 */
class AndroidTrackPlayer : TrackPlayer {
    @Volatile
    private var currentTrack: AudioTrack? = null

    override fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean) {
        val pcm = toPcm16(samples)
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "AudioTrack does not take $sampleRate Hz (getMinBufferSize=$minBufferSize)" }
        val track = buildTrack(sampleRate, maxOf(minBufferSize * BUFFER_FACTOR, DEFAULT_BUFFER_SIZE))
        currentTrack = track
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialized" }
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "AudioTrack did not start (playState=${track.playState})" }
            writeSamples(track, pcm, isCancelled)
            drainTrack(track, pcm.size, sampleRate, isCancelled)
        } finally {
            releaseTrack(track)
            if (currentTrack === track) {
                currentTrack = null
            }
        }
    }

    private fun buildTrack(sampleRate: Int, bufferSize: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeSamples(track: AudioTrack, samples: ShortArray, isCancelled: () -> Boolean) {
        var offset = 0
        while (offset < samples.size && !isCancelled()) {
            val size = minOf(CHUNK_SIZE, samples.size - offset)
            val written = track.write(samples, offset, size, AudioTrack.WRITE_BLOCKING)
            // A stopped track (stop()) writes 0; anything negative is an error code.
            if (written < 0 && !isCancelled()) error("AudioTrack.write failed: $written")
            if (written <= 0) break
            offset += written
        }
    }

    private fun drainTrack(track: AudioTrack, totalSamples: Int, sampleRate: Int, isCancelled: () -> Boolean) {
        if (isCancelled() || sampleRate <= 0) return
        val maxDrainMs = (totalSamples * 1000L / sampleRate) + DRAIN_PADDING_MS
        val deadline = System.currentTimeMillis() + maxDrainMs
        while (!isCancelled() &&
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition < totalSamples
        ) {
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(DRAIN_POLL_MS)
        }
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        } catch (_: Exception) {
            // Ignore stop error
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun releaseTrack(track: AudioTrack) {
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.release()
        } catch (_: Exception) {
            // Ignore cleanup error on track
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        try {
            currentTrack?.pause()
            currentTrack?.flush()
        } catch (_: Exception) {
            // Ignore stop errors
        }
    }

    companion object {
        /** Model output (-1..1) as 16-bit PCM, clipped. */
        fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }

        private const val BUFFER_FACTOR = 3
        private const val DEFAULT_BUFFER_SIZE = 4096
        private const val CHUNK_SIZE = 1024
        private const val DRAIN_POLL_MS = 10L
        private const val DRAIN_PADDING_MS = 250L
    }
}

/**
 * Plays synthesized speech waveforms using [AudioTrack] and manages Audio Focus.
 * Ducking lowers background music volume while speech is active.
 * Immediate [stop] releases focus and cancels active playback.
 */
class TtsPlayer(
    private val focusController: AudioFocusController,
    private val trackPlayer: TrackPlayer = AndroidTrackPlayer()
) {
    @Volatile
    private var isPlaying = false

    @Volatile
    private var cancelled = false

    val isSpeaking: Boolean get() = isPlaying

    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    fun play(
        samples: FloatArray,
        sampleRate: Int,
        isCancelled: () -> Boolean = { false },
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        synchronized(this) {
            if (isCancelled()) {
                cancelled = true
                return
            }
            cancelled = false
            isPlaying = true
        }
        try {
            // The car may mute a stream that has no focus; play anyway, but leave a trace.
            if (!focusController.requestFocus()) Log.w(TAG, "audio focus not granted, playing anyway")
            if (cancelled || isCancelled()) return
            onStart()
            trackPlayer.play(samples, sampleRate) { cancelled || isCancelled() }
            if (!cancelled && !isCancelled()) {
                onDone()
            }
        } catch (e: Exception) {
            if (!cancelled && !isCancelled()) {
                onError(e.message ?: "Playback error")
            }
        } finally {
            focusController.abandonFocus()
            synchronized(this) {
                isPlaying = false
            }
        }
    }

    fun stop() {
        cancelled = true
        trackPlayer.stop()
        focusController.abandonFocus()
        synchronized(this) {
            isPlaying = false
        }
    }

    companion object {
        private const val TAG = "TtsPlayer"

        fun create(context: Context): TtsPlayer {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return TtsPlayer(AndroidAudioFocusController(audioManager))
        }
    }
}
