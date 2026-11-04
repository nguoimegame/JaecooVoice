package com.example.jaecoovoice.engine

import com.example.jaecoovoice.engine.EndpointDetector.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDetectorTest {

    private val quiet = 0.002f
    private val loud = 0.2f

    private fun EndpointDetector.feed(rms: Float, frames: Int): Signal {
        var last = Signal.CONTINUE
        repeat(frames) { last = onFrame(rms) }
        return last
    }

    @Test
    fun endsAfterSilenceFollowingSpeech() {
        val detector = EndpointDetector(frameMs = 100, silenceMs = 800)
        detector.feed(quiet, 3)
        assertEquals(Signal.CONTINUE, detector.feed(loud, 10))
        assertEquals(Signal.CONTINUE, detector.feed(quiet, 7))
        assertEquals(Signal.END, detector.onFrame(quiet))
    }

    @Test
    fun silenceBeforeSpeechTimesOut() {
        val detector = EndpointDetector(frameMs = 100, noSpeechMs = 4_000)
        assertEquals(Signal.CONTINUE, detector.feed(quiet, 39))
        assertEquals(Signal.NO_SPEECH, detector.onFrame(quiet))
    }

    @Test
    fun longSpeechIsCutAtTheLimit() {
        val detector = EndpointDetector(frameMs = 100, maxMs = 2_000)
        assertEquals(Signal.CONTINUE, detector.feed(loud, 19))
        assertEquals(Signal.END, detector.onFrame(loud))
    }

    @Test
    fun aPauseInsideASentenceDoesNotEndIt() {
        val detector = EndpointDetector(frameMs = 100, silenceMs = 800)
        detector.feed(quiet, 3)
        detector.feed(loud, 5)
        assertEquals(Signal.CONTINUE, detector.feed(quiet, 5))
        assertEquals(Signal.CONTINUE, detector.feed(loud, 5))
    }

    @Test
    fun loudCarNoiseRaisesTheThreshold() {
        // Car noise floor 0.05: a level of 0.1 is not three times the floor, so it is not speech yet.
        val detector = EndpointDetector(frameMs = 100, noSpeechMs = 2_000)
        detector.feed(0.05f, 3)
        assertEquals(Signal.NO_SPEECH, detector.feed(0.1f, 17))
    }

    @Test
    fun speechStartedFlipsOnTheFirstLoudFrame() {
        val detector = EndpointDetector(frameMs = 100)
        detector.feed(0.001f, 3)
        assertFalse(detector.speechStarted)
        detector.onFrame(0.1f)
        assertTrue(detector.speechStarted)
    }
}
