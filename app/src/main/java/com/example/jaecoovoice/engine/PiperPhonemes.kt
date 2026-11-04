package com.example.jaecoovoice.engine

import java.text.Normalizer

/**
 * Turns espeak-ng clauses into Piper phoneme sentences and the codepoints sherpa-onnx takes
 * (`GenerationConfig.phonemeCodepoints`). A 1-1 port of phonemize_eSpeak in piper-phonemize
 * (src/phonemize.cpp), the phonemizer Piper models were trained with; the golden test in
 * EspeakPhonemizerTest holds the two together.
 */
object PiperPhonemes {

    // Clause terminators, from piper-phonemize src/phonemize.hpp (espeak-ng translate.h).
    const val CLAUSE_TYPE_SENTENCE = 0x00080000
    const val CLAUSE_PERIOD = 40 or 0x00000000 or CLAUSE_TYPE_SENTENCE
    const val CLAUSE_QUESTION = 40 or 0x00002000 or CLAUSE_TYPE_SENTENCE
    const val CLAUSE_EXCLAMATION = 45 or 0x00003000 or CLAUSE_TYPE_SENTENCE
    const val CLAUSE_COMMA = 20 or 0x00001000 or 0x00040000
    const val CLAUSE_COLON = 30 or 0x00000000 or 0x00040000
    const val CLAUSE_SEMICOLON = 30 or 0x00001000 or 0x00040000

    private const val PUNCTUATION_MASK = 0x000FFFFF

    /** piper-phonemize's DEFAULT_PHONEME_MAP: voice -> (phoneme -> replacement). */
    private val PHONEME_MAP: Map<String, Map<Int, String>> = mapOf("pt-br" to mapOf('c'.code to "k"))

    /** Sentences of NFD phonemes with Piper's punctuation; blank sentences are dropped. */
    fun sentences(clauses: List<EspeakClause>, voice: String): List<String> {
        val map = PHONEME_MAP[voice]
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (clause in clauses) {
            val nfd = Normalizer.normalize(clause.phonemes, Normalizer.Form.NFD)
            current.append(stripLanguageFlags(if (map == null) nfd else mapPhonemes(nfd, map)))
            current.append(punctuation(clause.terminator))
            if (clause.terminator and CLAUSE_TYPE_SENTENCE == CLAUSE_TYPE_SENTENCE) {
                out += current.toString()
                current.clear()
            }
        }
        out += current.toString()
        return out.filter { it.isNotBlank() }
    }

    /** One codepoint array per sentence. sherpa-onnx adds Piper's `^`, `_` and `$` itself. */
    fun toCodepoints(sentences: List<String>): Array<IntArray> = sentences.map { it.codePoints().toArray() }.toTypedArray()

    private fun punctuation(terminator: Int): String = when (terminator and PUNCTUATION_MASK) {
        CLAUSE_PERIOD -> "."
        CLAUSE_QUESTION -> "?"
        CLAUSE_EXCLAMATION -> "!"
        CLAUSE_COMMA -> ", "
        CLAUSE_COLON -> ": "
        CLAUSE_SEMICOLON -> "; "
        else -> ""
    }

    private fun mapPhonemes(text: String, map: Map<Int, String>): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp -> map[cp]?.let { out.append(it) } ?: out.appendCodePoint(cp) }
        return out.toString()
    }

    /** Drops `(en)`… `(vi)` language switches: everything from `(` to the next `)`, both included. */
    private fun stripLanguageFlags(text: String): String {
        val out = StringBuilder(text.length)
        var inFlag = false
        text.codePoints().forEach { cp ->
            when {
                inFlag -> if (cp == ')'.code) inFlag = false
                cp == '('.code -> inFlag = true
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }
}
