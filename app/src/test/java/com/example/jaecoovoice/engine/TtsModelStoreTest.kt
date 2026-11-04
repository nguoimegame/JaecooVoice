package com.example.jaecoovoice.engine

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class TtsModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, text) in entries) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private val testModel = TtsModel(
        id = "test-tts-vi",
        language = "vi",
        label = "Test TTS",
        url = "https://example.invalid/test-tts.tar.bz2",
        sizeBytes = 100L,
        archiveRoot = "test-root",
        modelFile = "model.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        sampleRate = 22050
    )

    @Test
    fun extractsNestedFilesAndSubdirectories() {
        val out = temp.newFolder("tts_out")
        val tar = archive(
            "test-root/model.onnx" to "onnx-data",
            "test-root/tokens.txt" to "tokens-data",
            "test-root/espeak-ng-data/lang/vi" to "vi-lang",
            "test-root/espeak-ng-data/phontab" to "phontab-data"
        )

        TtsModelStore.extractTarBz2(ByteArrayInputStream(tar), out, "test-root")

        assertTrue(out.resolve("model.onnx").exists())
        assertEquals("onnx-data", out.resolve("model.onnx").readText())
        assertTrue(out.resolve("tokens.txt").exists())
        assertEquals("tokens-data", out.resolve("tokens.txt").readText())
        assertTrue(out.resolve("espeak-ng-data/lang/vi").exists())
        assertEquals("vi-lang", out.resolve("espeak-ng-data/lang/vi").readText())
        assertTrue(out.resolve("espeak-ng-data/phontab").exists())
        assertEquals("phontab-data", out.resolve("espeak-ng-data/phontab").readText())
    }

    @Test
    fun entriesEscapingTargetFolderAreIgnored() {
        val out = temp.newFolder("tts_safe")
        val tar = archive(
            "test-root/../evil.txt" to "bad",
            "test-root/good.txt" to "good"
        )

        TtsModelStore.extractTarBz2(ByteArrayInputStream(tar), out, "test-root")

        assertTrue(out.resolve("good.txt").exists())
        assertFalse(out.parentFile!!.resolve("evil.txt").exists())
        assertFalse(out.resolve("evil.txt").exists())
    }

    @Test
    fun isInstalledDetectsMissingAndCompleteFiles() {
        val filesDir = temp.root
        val dir = TtsModelStore.dir(filesDir, testModel).apply { mkdirs() }

        assertFalse(TtsModelStore.isInstalled(filesDir, testModel))

        File(dir, "model.onnx").writeText("model")
        assertFalse(TtsModelStore.isInstalled(filesDir, testModel))

        File(dir, "tokens.txt").writeText("tokens")
        assertFalse(TtsModelStore.isInstalled(filesDir, testModel))

        File(dir, "espeak-ng-data").mkdirs()
        assertTrue(TtsModelStore.isInstalled(filesDir, testModel))
    }

    @Test
    fun installSucceedsAndUnpacksArchive() {
        val filesDir = temp.root
        val tar = archive(
            "test-root/model.onnx" to "onnx-bytes",
            "test-root/tokens.txt" to "tokens-bytes",
            "test-root/espeak-ng-data/data" to "espeak-data"
        )
        val goodModel = testModel.copy(sha256 = sha256(tar))
        val progressList = mutableListOf<Int>()

        val result = TtsModelStore.install(
            filesDir = filesDir,
            model = goodModel,
            onProgress = { progressList += it },
            open = { ByteArrayInputStream(tar) }
        )

        assertEquals(TtsModelStore.InstallResult.Success, result)
        assertTrue(TtsModelStore.isInstalled(filesDir, goodModel))
        assertEquals(100, progressList.last())
    }

    @Test
    fun deleteRemovesModelDirectory() {
        val filesDir = temp.root
        val dir = TtsModelStore.dir(filesDir, testModel).apply { mkdirs() }
        File(dir, "model.onnx").writeText("data")
        assertTrue(dir.exists())

        TtsModelStore.delete(filesDir, testModel)
        assertFalse(dir.exists())
    }
}
