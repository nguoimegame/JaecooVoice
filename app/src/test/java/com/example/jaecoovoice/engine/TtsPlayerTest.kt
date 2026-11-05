package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlayerTest {

    @Test
    fun `float samples become clipped 16-bit pcm`() {
        val pcm = AndroidTrackPlayer.toPcm16(floatArrayOf(0f, 1f, -1f, 0.5f, 2f, -3f))
        assertEquals(listOf<Short>(0, 32767, -32767, 16383, 32767, -32767), pcm.toList())
    }

    private class FakeFocusController : AudioFocusController {
        var focusRequested = 0
        var focusAbandoned = 0
        var hasFocus = false

        override fun requestFocus(): Boolean {
            focusRequested++
            hasFocus = true
            return true
        }

        override fun abandonFocus() {
            focusAbandoned++
            hasFocus = false
        }
    }

    private class FakeTrackPlayer : TrackPlayer {
        var playCount = 0
        var stopCount = 0
        var throwOnPlay: Exception? = null
        var onPlayCallback: (() -> Unit)? = null

        override fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean) {
            playCount++
            throwOnPlay?.let { throw it }
            onPlayCallback?.invoke()
        }

        override fun stop() {
            stopCount++
        }
    }

    @Test
    fun successfulPlaybackFlow() {
        val focus = FakeFocusController()
        val track = FakeTrackPlayer()
        val player = TtsPlayer(focus, track)

        var started = false
        var done = false
        var error: String? = null

        val samples = FloatArray(100) { 0.1f }
        player.play(
            samples = samples,
            sampleRate = 22050,
            onStart = { started = true },
            onDone = { done = true },
            onError = { error = it }
        )

        assertTrue("Should request audio focus", focus.focusRequested > 0)
        assertTrue("Should invoke onStart", started)
        assertEquals("Should invoke track.play", 1, track.playCount)
        assertTrue("Should invoke onDone", done)
        assertEquals("Should abandon focus when done", 1, focus.focusAbandoned)
        assertFalse("Should release focus", focus.hasFocus)
        assertFalse("isSpeaking should be false", player.isSpeaking)
        assertEquals(null, error)
    }

    @Test
    fun stopAbandonsFocusAndHaltsPlaybackImmediately() {
        val focus = FakeFocusController()
        val track = FakeTrackPlayer()
        val player = TtsPlayer(focus, track)

        var started = false
        var done = false

        track.onPlayCallback = {
            assertTrue("Should be speaking while in play callback", player.isSpeaking)
            player.stop()
        }

        player.play(
            samples = FloatArray(100),
            sampleRate = 22050,
            onStart = { started = true },
            onDone = { done = true }
        )

        assertTrue(started)
        assertFalse("onDone should not be called when stopped early", done)
        assertEquals("Track player stop called", 1, track.stopCount)
        assertFalse("Focus should be abandoned on stop", focus.hasFocus)
        assertFalse("isSpeaking should be false after stop", player.isSpeaking)
    }

    @Test
    fun errorAbandonsFocusAndInvokesOnError() {
        val focus = FakeFocusController()
        val track = FakeTrackPlayer().apply {
            throwOnPlay = IllegalStateException("AudioTrack failure")
        }
        val player = TtsPlayer(focus, track)

        var done = false
        var errorMessage: String? = null

        player.play(
            samples = FloatArray(100),
            sampleRate = 22050,
            onDone = { done = true },
            onError = { errorMessage = it }
        )

        assertFalse("onDone should not be called on error", done)
        assertEquals("AudioTrack failure", errorMessage)
        assertEquals("Focus should be abandoned on error", 1, focus.focusAbandoned)
        assertFalse("isSpeaking should be false after error", player.isSpeaking)
    }

    @Test
    fun preCancelledPlaybackExitsImmediatelyWithoutRequestingFocusOrPlaying() {
        val focus = FakeFocusController()
        val track = FakeTrackPlayer()
        val player = TtsPlayer(focus, track)

        var started = false
        var done = false

        player.play(
            samples = FloatArray(100),
            sampleRate = 22050,
            isCancelled = { true },
            onStart = { started = true },
            onDone = { done = true }
        )

        assertFalse("Should not start", started)
        assertFalse("Should not complete done", done)
        assertEquals("Should not request audio focus", 0, focus.focusRequested)
        assertEquals("Should not play track", 0, track.playCount)
        assertFalse("isSpeaking should be false", player.isSpeaking)
    }
}
