package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashTraceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun breadcrumb(step: String, at: Long) = File(temp.root, "tts-trace.txt").writeText("$step\n$at")

    private fun step() = File(temp.root, "tts-trace.txt").readLines().first()

    @Test
    fun aStepLeftByAnEarlierProcessIsReportedOnce() {
        breadcrumb("sherpa load model", at = 1_000L)
        assertEquals(
            "during 'sherpa load model' (4s before)",
            CrashTrace.interruption(temp.root, processStartMs = 2_000L, nowMs = 5_000L)
        )
        assertEquals(CrashTrace.DONE, step())
        assertNull(CrashTrace.interruption(temp.root, processStartMs = 2_000L, nowMs = 6_000L))
    }

    /** This process is still running that step (a warmUp on the TTS thread): nobody died. */
    @Test
    fun aStepOfThisProcessIsNotADeathAndIsLeftAlone() {
        breadcrumb("sherpa load model", at = 3_000L)
        assertNull(CrashTrace.interruption(temp.root, processStartMs = 2_000L, nowMs = 5_000L))
        assertEquals("sherpa load model", step())
    }

    @Test
    fun doneOrNoBreadcrumbIsNotADeath() {
        assertNull(CrashTrace.interruption(temp.root, processStartMs = 2_000L, nowMs = 5_000L))
        breadcrumb(CrashTrace.DONE, at = 1_000L)
        assertNull(CrashTrace.interruption(temp.root, processStartMs = 2_000L, nowMs = 5_000L))
    }
}
