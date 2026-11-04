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

class ModelStoreTest {

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

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val model = VoiceModel(
        id = "m1",
        language = "vi",
        label = "Test",
        url = "https://example.invalid/m1.tar.bz2",
        sha256 = "",
        sizeBytes = 10L,
        archiveRoot = "root",
        files = setOf("a.onnx", "tokens.txt")
    )

    @Test
    fun extractsOnlyTheWantedFilesUnderTheRoot() {
        val out = temp.newFolder("model")
        val tar = archive(
            "root/encoder.int8.onnx" to "enc",
            "root/tokens.txt" to "tok",
            "root/README.md" to "readme",
            "root/test_wavs/0.wav" to "wav"
        )

        ModelStore.extractTarBz2(ByteArrayInputStream(tar), out, "root", setOf("encoder.int8.onnx", "tokens.txt"))

        assertEquals(setOf("encoder.int8.onnx", "tokens.txt"), out.list()!!.toSet())
        assertEquals("enc", out.resolve("encoder.int8.onnx").readText())
    }

    @Test
    fun renamesArchiveFilesToTheNamesTheEngineLoads() {
        val out = temp.newFolder("model")
        val tar = archive("root/encoder-epoch-99-avg-1.int8.onnx" to "enc", "root/encoder-epoch-99-avg-1.onnx" to "big")

        ModelStore.extractTarBz2(
            ByteArrayInputStream(tar),
            out,
            "root",
            setOf("encoder.int8.onnx"),
            mapOf("encoder-epoch-99-avg-1.int8.onnx" to "encoder.int8.onnx")
        )

        assertEquals(setOf("encoder.int8.onnx"), out.list()!!.toSet())
        assertEquals("enc", out.resolve("encoder.int8.onnx").readText())
    }

    @Test
    fun entriesEscapingTheFolderAreIgnored() {
        val out = temp.newFolder("model")
        val tar = archive("root/../evil.txt" to "bad", "root/ok.txt" to "ok")

        ModelStore.extractTarBz2(ByteArrayInputStream(tar), out, "root", setOf("ok.txt", "../evil.txt", "evil.txt"))

        assertTrue(out.resolve("ok.txt").exists())
        assertFalse(out.parentFile!!.resolve("evil.txt").exists())
        assertFalse(out.resolve("evil.txt").exists())
    }

    @Test
    fun modelsLiveUnderTheirLanguage() {
        val filesDir = temp.root
        assertEquals(
            File(filesDir, "models/vi/zipformer-vi-30M-int8-2026-02-09"),
            ModelStore.dir(filesDir, ModelCatalog.VI_30M)
        )
    }

    @Test
    fun aPartialModelIsNotInstalled() {
        val filesDir = temp.root
        val dir = ModelStore.dir(filesDir, model).apply { mkdirs() }
        File(dir, "tokens.txt").writeText("tok")
        assertFalse(ModelStore.isInstalled(filesDir, model))
        File(dir, "a.onnx").writeText("a")
        assertTrue(ModelStore.isInstalled(filesDir, model))
    }

    @Test
    fun aGoodArchiveInstalls() {
        val filesDir = temp.root
        val tar = archive("root/a.onnx" to "a", "root/tokens.txt" to "tok")
        val good = model.copy(sha256 = sha256(tar))
        val progress = mutableListOf<Int>()

        val result = ModelStore.install(filesDir, good, { progress += it }) { ByteArrayInputStream(tar) }

        assertEquals(ModelStore.InstallResult.Success, result)
        assertTrue(ModelStore.isInstalled(filesDir, good))
        assertEquals(100, progress.last())
    }

    @Test
    fun wrongChecksumLeavesNothingInstalled() {
        val filesDir = temp.root
        val tar = archive("root/a.onnx" to "a", "root/tokens.txt" to "tok")
        val bad = model.copy(sha256 = "0".repeat(64))

        val result = ModelStore.install(filesDir, bad, {}) { ByteArrayInputStream(tar) }

        assertTrue(result is ModelStore.InstallResult.Failed)
        assertFalse(ModelStore.dir(filesDir, bad).exists())
        val leftovers = File(filesDir, "models/vi").listFiles().orEmpty().map { it.name }
        assertTrue(leftovers.toString(), leftovers.none { it.endsWith(".staging") || it.endsWith(".download") })
    }

    @Test
    fun deleteRemovesTheModel() {
        val filesDir = temp.root
        val dir = ModelStore.dir(filesDir, model).apply { mkdirs() }
        File(dir, "a.onnx").writeText("a")
        ModelStore.delete(filesDir, model)
        assertFalse(dir.exists())
    }
}
