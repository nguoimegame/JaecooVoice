package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCrashHandlerTest {

    @Test
    fun recordsTheCrashThenDelegatesToThePreviousHandler() {
        var crashed: Throwable? = null
        var delegated: Throwable? = null
        val handler = VoiceCrashHandler(previous = { _, e -> delegated = e }, crash = { _, e -> crashed = e })
        val boom = IllegalStateException("boom")
        handler.uncaughtException(Thread.currentThread(), boom)
        assertEquals(boom, crashed)
        assertEquals(boom, delegated)
    }

    @Test
    fun crashStillReachesThePreviousHandlerWhenWritingFails() {
        var delegated = false
        val handler = VoiceCrashHandler(previous = { _, _ -> delegated = true }, crash = { _, _ -> throw OutOfMemoryError("while crashing") })
        handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertTrue(delegated)
    }

    @Test
    fun worksWithoutAPreviousHandler() {
        var crashes = 0
        VoiceCrashHandler(previous = null, crash = { _, _ -> crashes++ }).uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(1, crashes)
    }

    @Test
    fun theReportOfThePreviousRunIsHandedOnOnce() {
        PreviousRun.report = "previous run died during 'sherpa load model'"
        assertEquals("previous run died during 'sherpa load model'", PreviousRun.take())
        assertEquals(null, PreviousRun.take())
    }
}
