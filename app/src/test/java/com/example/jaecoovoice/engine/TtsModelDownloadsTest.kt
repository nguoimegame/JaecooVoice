package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsModelDownloadsTest {

    private class Recorder : ModelListener {
        val progress = mutableListOf<Int>()
        var done = false
        var error: String? = null
        override fun onProgress(percent: Int) {
            progress += percent
        }
        override fun onDone() {
            done = true
        }
        override fun onError(message: String) {
            error = message
        }
    }

    private val viModel = TtsCatalog.VI_VAIS1000
    private val executor = ManualExecutor()

    @Test
    fun secondStartJoinsTheRunningDownload() {
        var installs = 0
        val downloads = TtsModelDownloads(executor) { _, onProgress ->
            installs++
            onProgress(40)
            TtsModelStore.InstallResult.Success
        }
        val first = Recorder()
        val second = Recorder()

        downloads.start(viModel, first)
        downloads.start(viModel, second)

        assertTrue(downloads.isDownloading("vi"))
        assertEquals(1, executor.queued)
        executor.runAll()
        assertEquals(1, installs)
        assertEquals(listOf(40), first.progress)
        assertEquals(listOf(40), second.progress)
        assertTrue(first.done && second.done)
        assertFalse(downloads.isDownloading("vi"))
    }

    @Test
    fun failureReachesEveryListener() {
        val downloads = TtsModelDownloads(executor) { _, _ ->
            TtsModelStore.InstallResult.Failed("download-error")
        }
        val first = Recorder()
        val second = Recorder()

        downloads.start(viModel, first)
        downloads.start(viModel, second)
        executor.runAll()

        assertEquals("download-error", first.error)
        assertEquals("download-error", second.error)
        assertFalse(first.done)
        assertFalse(downloads.isDownloading("vi"))
    }

    @Test
    fun unexpectedExceptionCleansUpRunningMap() {
        var callCount = 0
        val downloads = TtsModelDownloads(executor) { _, _ ->
            callCount++
            if (callCount == 1) {
                error("Network crashed")
            } else {
                TtsModelStore.InstallResult.Success
            }
        }
        val first = Recorder()
        downloads.start(viModel, first)
        executor.runAll()

        assertEquals("Network crashed", first.error)
        assertFalse("Running map must be cleaned up on exception", downloads.isDownloading("vi"))

        val second = Recorder()
        downloads.start(viModel, second)
        executor.runAll()
        assertTrue("Subsequent download must succeed after exception", second.done)
        assertEquals(2, callCount)
    }
}
