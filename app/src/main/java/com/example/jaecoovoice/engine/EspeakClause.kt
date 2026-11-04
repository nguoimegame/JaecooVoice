package com.example.jaecoovoice.engine

/**
 * One clause espeak-ng returned: its IPA phonemes and the clause terminator flags
 * (`espeak_TextToPhonemesWithTerminator`).
 */
data class EspeakClause(val terminator: Int, val phonemes: String) {
    companion object {
        /** Parses `"<terminator in decimal>|<phonemes>"`, splitting at the first `|`. */
        fun parse(line: String): EspeakClause {
            val bar = line.indexOf('|')
            require(bar > 0) { "not a clause line: $line" }
            return EspeakClause(line.substring(0, bar).toInt(), line.substring(bar + 1))
        }
    }
}
