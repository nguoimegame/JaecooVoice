package com.example.jaecoovoice.engine

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Models are not packed in the APK: they download to `filesDir/models/<language>/<id>/`. The
 * archive is checked against its sha256 and unpacked into a staging folder that is renamed only
 * at the end, so a broken or interrupted download never looks installed.
 */
object ModelStore {

    private const val TAG = "ModelStore"
    private const val BUFFER = 64 * 1024
    private const val PERCENT = 100

    /** Leaves a few percent for unpacking. */
    private const val DOWNLOAD_SHARE = 95

    sealed class InstallResult {
        object Success : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    private fun languageDir(filesDir: File, model: VoiceModel) = File(File(filesDir, "models"), model.language)

    fun dir(filesDir: File, model: VoiceModel) = File(languageDir(filesDir, model), model.id)

    fun isInstalled(filesDir: File, model: VoiceModel): Boolean = model.files.all { File(dir(filesDir, model), it).length() > 0L }

    fun delete(filesDir: File, model: VoiceModel) {
        dir(filesDir, model).deleteRecursively()
    }

    /**
     * Downloads and installs [model]. Blocks (network, disk): call off the main thread.
     * [onProgress] gets 0–100; unpacking counts towards the last few percent. [open] fetches a URL
     * (tests pass their own).
     */
    fun install(
        filesDir: File,
        model: VoiceModel,
        onProgress: (Int) -> Unit,
        open: (String) -> InputStream = HttpSource::open
    ): InstallResult {
        val root = languageDir(filesDir, model).apply { mkdirs() }
        val archive = File(root, "${model.id}.download")
        val staging = File(root, "${model.id}.staging")
        return try {
            download(open(model.url), model.sizeBytes, archive, onProgress)
            check(sha256(archive) == model.sha256) { "Gói tải về bị hỏng (sha256 không khớp)" }
            staging.deleteRecursively()
            archive.inputStream().use { extractTarBz2(it, staging, model.archiveRoot, model.files, model.archiveNames) }
            check(model.files.all { File(staging, it).length() > 0L }) { "Gói tải về thiếu file mô hình" }
            val target = dir(filesDir, model)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Không đổi tên được thư mục mô hình" }
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

    private fun download(input: InputStream, expectedBytes: Long, into: File, onProgress: (Int) -> Unit) {
        val total = (input as? HttpSource.SizedStream)?.length?.takeIf { it > 0 } ?: expectedBytes
        input.use { into.outputStream().use { output -> copyWithProgress(input, output, total, onProgress) } }
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
            if (percent != lastPercent) onProgress(percent).also { lastPercent = percent }
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
     * Unpacks into [outDir] only the [wanted] files under the archive's top folder [root]. Every
     * other entry is skipped, including any whose path points outside [outDir] (zip-slip).
     * [renames] maps an archive file name to the name it is saved under.
     */
    internal fun extractTarBz2(
        input: InputStream,
        outDir: File,
        root: String,
        wanted: Set<String>,
        renames: Map<String, String> = emptyMap()
    ) {
        outDir.mkdirs()
        val canonicalOut = outDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val archived = entry.name.removePrefix("./").removePrefix("$root/")
                val name = renames[archived] ?: archived
                val target = File(outDir, name).canonicalFile
                if (!entry.isDirectory && name in wanted && target.parentFile == canonicalOut) copyEntry(tar, target)
            }
        }
    }

    private fun copyEntry(tar: InputStream, target: File) {
        target.outputStream().use { tar.copyTo(it) }
    }
}
