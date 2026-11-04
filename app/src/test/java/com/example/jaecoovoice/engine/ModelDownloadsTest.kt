package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadsTest {

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

    private val vi = ModelCatalog.VI_30M
    private val executor = ManualExecutor()

    @Test
    fun secondStartJoinsTheRunningDownload() {
        var installs = 0
        val downloads = ModelDownloads(executor) { _, onProgress ->
            installs++
            onProgress(40)
            ModelStore.InstallResult.Success
        }
        val first = Recorder()
        val second = Recorder()

        downloads.start(vi, first)
        downloads.start(vi, second)

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
        val downloads = ModelDownloads(executor) { _, _ -> ModelStore.InstallResult.Failed("x") }
        val first = Recorder()
        val second = Recorder()

        downloads.start(vi, first)
        downloads.start(vi, second)
        executor.runAll()

        assertEquals("x", first.error)
        assertEquals("x", second.error)
        assertFalse(first.done)
    }

    @Test
    fun aNewStartAfterTheEndDownloadsAgain() {
        var installs = 0
        val downloads = ModelDownloads(executor) { _, _ ->
            installs++
            ModelStore.InstallResult.Success
        }
        downloads.start(vi, Recorder())
        executor.runAll()
        downloads.start(vi, Recorder())
        executor.runAll()
        assertEquals(2, installs)
    }
}
