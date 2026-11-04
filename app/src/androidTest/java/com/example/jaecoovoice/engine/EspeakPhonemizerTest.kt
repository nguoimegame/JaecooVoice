package com.example.jaecoovoice.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the native espeak-ng phonemizer against golden phonemes written by
 * `tools/phonemes/make_golden.py` (piper_phonemize, the phonemizer Piper models were trained with).
 * The test APK carries a trimmed espeak-ng-data (vi + en) from the vi Piper model archive.
 * The golden comparison itself (`goldenSentencesMatch`) comes with PiperPhonemes.sentences (Task 2).
 */
@RunWith(AndroidJUnit4::class)
class EspeakPhonemizerTest {

    private data class Golden(val voice: String, val text: String, val sentences: List<String>)

    private lateinit var dataDir: File
    private lateinit var golden: List<Golden>

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        dataDir = File(instrumentation.targetContext.cacheDir, "espeak-ng-data")
        dataDir.deleteRecursively()
        copyAssets(instrumentation.context.assets, "espeak-ng-data", dataDir)
        val json = instrumentation.context.assets.open("phonemes/golden.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        golden = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val s = o.getJSONArray("sentences")
            Golden(o.getString("voice"), o.getString("text"), (0 until s.length()).map { s.getString(it) })
        }
    }

    @Test
    fun everyGoldenTextGivesClauses() {
        val phonemizer = EspeakPhonemizer(dataDir)
        for (g in golden) {
            val clauses = phonemizer.clauses(g.text, g.voice)
            assertNotNull("null for ${g.voice}: ${g.text}", clauses)
            assertTrue("no clauses for ${g.voice}: ${g.text}", clauses!!.isNotEmpty())
            android.util.Log.i("EspeakPhonemizerTest", "${g.voice} ${g.text} -> $clauses")
        }
    }

    @Test
    fun unknownVoiceReturnsNull() {
        assertNull(EspeakPhonemizer(dataDir).clauses("xin chào", "xx-nope"))
    }

    @Test
    fun missingDataDirReturnsNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(EspeakPhonemizer(File(context.cacheDir, "nope")).clauses("xin chào", "vi"))
    }

    @Test
    fun concurrentCallsMatch() {
        val g = golden.first()
        val expected = EspeakPhonemizer(dataDir).clauses(g.text, g.voice)
        assertNotNull(expected)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val results = pool.invokeAll(
                (1..THREADS).map {
                    Callable { (1..CALLS).map { EspeakPhonemizer(dataDir).clauses(g.text, g.voice) } }
                },
                TIMEOUT_S,
                TimeUnit.SECONDS
            ).flatMap { it.get() }
            assertEquals(THREADS * CALLS, results.size)
            results.forEach { assertEquals(expected, it) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun copyAssets(assets: android.content.res.AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssets(assets, "$path/$it", File(target, it)) }
    }

    private companion object {
        const val THREADS = 8
        const val CALLS = 20
        const val TIMEOUT_S = 120L
    }
}
