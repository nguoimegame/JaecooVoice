package com.example.jaecoovoice.engine

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Downloads and manages offline TTS models under `filesDir/tts_models/<language>/<id>/`.
 * Ensures thread-safe atomic staging so interrupted downloads are never marked installed.
 */
object TtsModelStore {

    private const val TAG = "TtsModelStore"
    private const val BUFFER = 64 * 1024
    private const val PERCENT = 100
    private const val DOWNLOAD_SHARE = 95

    sealed class InstallResult {
        object Success : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    fun dir(filesDir: File, model: TtsModel): File = File(File(File(filesDir, "tts_models"), model.language), model.id)

    /** The espeak-ng-data shared by every Piper model (see [EspeakData]). */
    fun sharedEspeakDataDir(filesDir: File): File = EspeakData.dir(filesDir)

    /**
     * True when [model]'s files are complete. A Piper model also needs the shared espeak-ng-data;
     * a copy left in the model folder by JaecooVoice 1.3.x is moved there first.
     */
    fun isInstalled(filesDir: File, model: TtsModel): Boolean {
        val target = dir(filesDir, model)
        val modelOk = File(target, model.modelFile).length() > 0L
        val tokensOk = File(target, model.tokensFile).length() > 0L
        val lexiconOk = model.lexiconFile == null || File(target, model.lexiconFile).length() > 0L
        val filesOk = modelOk && tokensOk && lexiconOk
        if (!filesOk || model.frontend != TtsFrontend.ESPEAK) return filesOk
        EspeakData.adopt(model, filesDir, target)
        return EspeakData.isPresent(sharedEspeakDataDir(filesDir))
    }

    fun delete(filesDir: File, model: TtsModel) {
        dir(filesDir, model).deleteRecursively()
    }

    /**
     * Downloads and unpacks [model] into `filesDir/tts_models/<language>/<id>/`.
     * Blocks the calling thread; invoke off the main thread.
     */
    fun install(
        filesDir: File,
        model: TtsModel,
        onProgress: (Int) -> Unit,
        open: (String) -> InputStream = HttpSource::open
    ): InstallResult {
        val root = File(File(filesDir, "tts_models"), model.language).apply { mkdirs() }
        val archive = File(root, "${model.id}.download")
        val staging = File(root, "${model.id}.staging")
        return try {
            val input = open(model.url)
            val total = (input as? HttpSource.SizedStream)?.length?.takeIf { it > 0 } ?: model.sizeBytes
            input.use { inStream ->
                archive.outputStream().use { outStream ->
                    copyWithProgress(inStream, outStream, total, onProgress)
                }
            }
            if (model.sha256.isNotBlank()) {
                check(sha256(archive) == model.sha256) { "Gói tải về bị hỏng (sha256 không khớp)" }
            }
            staging.deleteRecursively()
            archive.inputStream().use { extractTarBz2(it, staging, model.archiveRoot) }
            val modelOk = File(staging, model.modelFile).length() > 0L
            val tokensOk = File(staging, model.tokensFile).length() > 0L
            check(modelOk && tokensOk) { "Gói tải về thiếu file mô hình TTS" }
            EspeakData.checkArchive(model, filesDir, staging)
            val target = dir(filesDir, model)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Không đổi tên được thư mục mô hình TTS" }
            EspeakData.adopt(model, filesDir, target)
            onProgress(PERCENT)
            InstallResult.Success
        } catch (e: IOException) {
            Log.w(TAG, "install failed", e)
            InstallResult.Failed(e.message ?: "Lỗi mạng")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "install failed", e)
            InstallResult.Failed(e.message ?: "Lỗi")
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var done = 0L
        var lastPercent = -1
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            val percent = (done * DOWNLOAD_SHARE / total.coerceAtLeast(1)).toInt().coerceAtMost(DOWNLOAD_SHARE)
            if (percent != lastPercent) {
                onProgress(percent)
                lastPercent = percent
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unpacks all entries from [input] that live under [root] into [outDir],
     * maintaining subdirectories (such as espeak-ng-data).
     * Protected against zip-slip.
     */
    internal fun extractTarBz2(input: InputStream, outDir: File, root: String) {
        outDir.mkdirs()
        val canonicalOut = outDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                extractEntry(tar, entry, outDir, canonicalOut, root)
            }
        }
    }

    private fun extractEntry(
        tar: InputStream,
        entry: TarArchiveEntry,
        outDir: File,
        canonicalOut: File,
        root: String
    ) {
        val archived = entry.name.removePrefix("./").removePrefix("$root/").removePrefix(root)
        if (archived.isBlank()) return
        val target = File(outDir, archived).canonicalFile
        if (!target.canonicalPath.startsWith(canonicalOut.canonicalPath)) return
        if (entry.isDirectory) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            target.outputStream().use { tar.copyTo(it) }
        }
    }
}
