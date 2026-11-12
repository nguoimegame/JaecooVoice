package com.example.jaecoovoice.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale

/** How [SherpaSpeechEngine] decodes: [beam] = `modified_beam_search` with hotwords, otherwise `greedy_search`. */
internal data class Decoding(val beam: Boolean) {
    val method: String get() = if (beam) "modified_beam_search" else "greedy_search"
}

/**
 * Beam search (the only decoder that takes hotwords) only for a transducer that has its BPE vocabulary
 * on disk. Everything else keeps `greedy_search`. sherpa-onnx ends the process, it does not throw, when
 * the vocabulary file is missing, so this check has to come before the config is built.
 */
internal fun decodingFor(family: ModelFamily, vocab: File?): Decoding = Decoding(beam = family == ModelFamily.TRANSDUCER && vocab != null && vocab.length() > 0L)

/** The BPE vocabulary a model ships in the APK, copied next to the app's files where sherpa-onnx can open it. */
internal object HotwordVocab {
    private const val TAG = "HotwordVocab"

    fun file(context: Context, model: VoiceModel): File? {
        val asset = model.hotwordVocabAsset ?: return null
        val target = File(File(context.filesDir, "hotwords"), "${model.id}.vocab")
        return try {
            if (target.length() == 0L) copy(context, asset, target)
            target.takeIf { it.length() > 0L }
        } catch (e: IOException) {
            Log.w(TAG, "cannot copy the vocabulary of ${model.id}", e)
            null
        }
    }

    private fun copy(context: Context, asset: String, target: File) {
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, target.name + ".tmp")
        context.assets.open(asset).use { input -> staging.outputStream().use { input.copyTo(it) } }
        if (!staging.renameTo(target)) throw IOException("cannot rename ${staging.name}")
    }
}

/**
 * Turns the phrases JaecooApp sends into sherpa-onnx's hotword string: one phrase per line, upper case
 * (the model's tokens are upper case). A phrase with a letter the model has no token for is dropped, so
 * nothing odd ever reaches the native code.
 */
internal object HotwordFormat {
    const val MAX = 500

    /** Every character that occurs in the model's token pieces (`tokens.txt`: `piece id` per line). */
    fun alphabet(tokensFile: File): Set<Char> = alphabet(tokensFile.readLines())

    fun alphabet(tokenLines: List<String>): Set<Char> {
        val chars = HashSet<Char>()
        for (line in tokenLines) {
            val piece = line.substringBeforeLast(' ')
            if (piece.startsWith("<")) continue
            piece.forEach { if (it != WORD_MARK) chars += it }
        }
        return chars
    }

    /** What came over the binder: no nulls, no more than [MAX]. */
    fun clean(raw: List<String?>?): List<String> = raw.orEmpty().filterNotNull().take(MAX)

    /** The hotword string, or "" when no phrase can be used. */
    fun encode(phrases: List<String>, alphabet: Set<Char>): String {
        val usable = LinkedHashSet<String>()
        for (phrase in phrases) {
            val text = phrase.uppercase(Locale.ROOT).trim().replace(SPACES, " ")
            if (text.length > 1 && text.all { it == ' ' || it in alphabet }) usable += text
            if (usable.size == MAX) break
        }
        return usable.joinToString("\n")
    }

    private const val WORD_MARK = '▁'
    private val SPACES = Regex("\\s+")
}
