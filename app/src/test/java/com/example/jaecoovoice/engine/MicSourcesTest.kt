package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MicSourcesTest {

    @Test
    fun quietMicMovesToTheNextSource() = assertEquals(1, MicSources.next(0, 0.001f))

    @Test
    fun lastSourceWrapsAround() = assertEquals(0, MicSources.next(3, 0f))

    @Test
    fun aLiveMicKeepsItsSource() = assertEquals(2, MicSources.next(2, 0.01f))

    @Test
    fun outOfRangeIndexIsTreatedModulo() = assertEquals(1, MicSources.next(4, 0f))

    @Test
    fun namesFollowTheSources() {
        assertEquals("VOICE_RECOGNITION", MicSources.name(0))
        assertEquals("CAMCORDER", MicSources.name(3))
        assertEquals("VOICE_RECOGNITION", MicSources.name(4))
    }

    @Test
    fun candidatesPlacesPreferredFirstAndContainsAll() {
        val candidates = MicSources.candidates(android.media.MediaRecorder.AudioSource.MIC)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, candidates.first())
        assertEquals(MicSources.SOURCES.size, candidates.size)
        assertEquals(MicSources.SOURCES.size, candidates.distinct().size)
    }

    @Test
    fun indexOfSourceFindsKnownSourcesAndDefaultsToZero() {
        assertEquals(0, MicSources.indexOfSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertEquals(1, MicSources.indexOfSource(android.media.MediaRecorder.AudioSource.MIC))
        assertEquals(0, MicSources.indexOfSource(999))
    }
}
