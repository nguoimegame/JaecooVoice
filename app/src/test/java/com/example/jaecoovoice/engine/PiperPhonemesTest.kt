package com.example.jaecoovoice.engine

import com.example.jaecoovoice.engine.PiperPhonemes.CLAUSE_COLON
import com.example.jaecoovoice.engine.PiperPhonemes.CLAUSE_COMMA
import com.example.jaecoovoice.engine.PiperPhonemes.CLAUSE_EXCLAMATION
import com.example.jaecoovoice.engine.PiperPhonemes.CLAUSE_PERIOD
import com.example.jaecoovoice.engine.PiperPhonemes.CLAUSE_QUESTION
import com.example.jaecoovoice.engine.PiperPhonemes.CLAUSE_SEMICOLON
import org.junit.Assert.assertEquals
import org.junit.Test

class PiperPhonemesTest {

    private fun c(terminator: Int, phonemes: String) = EspeakClause(terminator, phonemes)

    private fun sentences(vararg clauses: EspeakClause, voice: String = "vi") = PiperPhonemes.sentences(clauses.toList(), voice)

    @Test
    fun constantsMatchWhatEspeakReturned() {
        // Terminators logged from libjaecoo-espeak on the emulator for the golden texts.
        assertEquals(266260, CLAUSE_COMMA)
        assertEquals(262174, CLAUSE_COLON)
        assertEquals(266270, CLAUSE_SEMICOLON)
        assertEquals(532520, CLAUSE_QUESTION)
        assertEquals(536621, CLAUSE_EXCLAMATION)
    }

    @Test
    fun periodEndsSentence() {
        assertEquals(
            listOf("sˈin tʃˈaːw.", "tˈɔj."),
            sentences(c(CLAUSE_PERIOD, "sˈin tʃˈaːw"), c(CLAUSE_PERIOD, "tˈɔj"))
        )
    }

    @Test
    fun periodFlagsAboveTheLow20BitsStillCount() {
        // "gì." mid-text came back as 4718632 = 0x480028: period plus a flag above 0xFFFFF.
        assertEquals(listOf("zˈi2.", "hˈəːn"), sentences(c(4718632, "zˈi2"), c(589864, "hˈəːn")))
    }

    @Test
    fun commaColonSemicolonStayInSentence() {
        assertEquals(
            listOf("a: b, c; d."),
            sentences(c(CLAUSE_COLON, "a"), c(CLAUSE_COMMA, "b"), c(CLAUSE_SEMICOLON, "c"), c(CLAUSE_PERIOD, "d"))
        )
    }

    @Test
    fun questionAndExclamation() {
        assertEquals(listOf("a?", "b!"), sentences(c(CLAUSE_QUESTION, "a"), c(CLAUSE_EXCLAMATION, "b")))
    }

    @Test
    fun lastClauseWithoutSentenceFlagIsKept() {
        assertEquals(listOf("a, b"), sentences(c(CLAUSE_COMMA, "a"), c(0, "b")))
    }

    @Test
    fun languageFlagsAreStripped() {
        assertEquals(
            listOf("mˈəː4 spˈɒtɪfˌaɪ"),
            sentences(c(589864, "mˈəː4 (en)spˈɒtɪfˌaɪ(vi)"))
        )
    }

    @Test
    fun blankTextGivesNoSentences() {
        assertEquals(emptyList<String>(), sentences())
        assertEquals(emptyList<String>(), sentences(c(589864, "  ")))
    }

    @Test
    fun sentencesUseNfd() {
        assertEquals(
            listOf(0x65, 0x303, 0x2E),
            PiperPhonemes.toCodepoints(sentences(c(CLAUSE_PERIOD, "ẽ")))[0].toList()
        )
    }

    @Test
    fun ptBrMapsCToKAfterNfd() {
        assertEquals(listOf("ķa."), sentences(c(CLAUSE_PERIOD, "ça"), voice = "pt-br"))
        assertEquals(listOf("ça."), sentences(c(CLAUSE_PERIOD, "ça")))
    }

    @Test
    fun toCodepointsKeepsSupplementaryCharacters() {
        assertEquals(listOf(0x1D43, 0x10348), PiperPhonemes.toCodepoints(listOf("ᵃ𐍈"))[0].toList())
    }

    @Test
    fun parseSplitsAtFirstBar() {
        assertEquals(EspeakClause(524328, "a|b"), EspeakClause.parse("524328|a|b"))
    }
}
