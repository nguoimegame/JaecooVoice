package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class HotwordsTest {

    private val tokens = listOf("<blk> 0", "<sos/eos> 1", "<unk> 2", "▁HAI 3", "▁KHÔNG 4", "S 5", "P 6", "O 7", "TI 8", "F 9", "Y 10", "▁ĐIỀU 11", "▁HÒA 12")
    private val alphabet = HotwordFormat.alphabet(tokens)

    @Test
    fun alphabetIsTheLettersOfThePieces() {
        assertTrue('Ô' in alphabet && 'Đ' in alphabet && 'F' in alphabet)
        assertFalse('▁' in alphabet)
        assertFalse('<' in alphabet)
        assertFalse('0' in alphabet)
    }

    @Test
    fun encodeUpperCasesAndJoinsByLine() {
        assertEquals("ĐIỀU HÒA\nSPOTIFY", HotwordFormat.encode(listOf("điều  hòa", "Spotify"), alphabet))
    }

    @Test
    fun encodeDropsPhrasesWithALetterTheModelHasNoTokenFor() {
        assertEquals("SPOTIFY", HotwordFormat.encode(listOf("zalo", "😀 spotify", "spotify", "x"), alphabet))
    }

    @Test
    fun encodeDropsDuplicatesAndOneLetterPhrases() {
        assertEquals("HAI", HotwordFormat.encode(listOf("hai", "HAI", "s", "  "), alphabet))
    }

    @Test
    fun encodeOfNothingUsableIsEmpty() {
        assertEquals("", HotwordFormat.encode(listOf("zzz", ""), alphabet))
        assertEquals("", HotwordFormat.encode(emptyList(), alphabet))
    }

    @Test
    fun encodeStopsAtTheLimit() {
        val many = (1..HotwordFormat.MAX + 20).map { "hai " + "s".repeat(it) }
        assertEquals(HotwordFormat.MAX, HotwordFormat.encode(many, alphabet).lines().size)
    }

    @Test
    fun cleanDropsNullsAndCaps() {
        assertEquals(listOf("a b"), HotwordFormat.clean(listOf(null, "a b", null)))
        assertEquals(emptyList<String>(), HotwordFormat.clean(null))
        assertEquals(HotwordFormat.MAX, HotwordFormat.clean(List(HotwordFormat.MAX + 5) { "x$it" }).size)
    }

    @Test
    fun beamSearchOnlyForATransducerWithItsVocabulary() {
        val dir = createTempDirectory().toFile()
        val vocab = File(dir, "v.vocab").apply { writeText("▁A\t0.0\n") }
        val empty = File(dir, "e.vocab").apply { writeText("") }
        assertTrue(decodingFor(ModelFamily.TRANSDUCER, vocab).beam)
        assertEquals("modified_beam_search", decodingFor(ModelFamily.TRANSDUCER, vocab).method)
        assertFalse(decodingFor(ModelFamily.TRANSDUCER, null).beam)
        assertFalse(decodingFor(ModelFamily.TRANSDUCER, empty).beam)
        assertFalse(decodingFor(ModelFamily.TRANSDUCER, File(dir, "missing")).beam)
        assertEquals("greedy_search", decodingFor(ModelFamily.NEMO_TRANSDUCER, vocab).method)
        assertFalse(decodingFor(ModelFamily.SENSE_VOICE, vocab).beam)
        assertFalse(decodingFor(ModelFamily.OMNILINGUAL_CTC, vocab).beam)
        dir.deleteRecursively()
    }

    @Test
    fun onlyTheVietnameseModelShipsAVocabulary() {
        assertTrue(ModelCatalog.VI_30M.hotwordVocabAsset != null)
        assertTrue(ModelCatalog.EN_SMALL.hotwordVocabAsset == null)
    }

    @Test
    fun viModelStaysInstalledWithoutAnyExtraFile() {
        assertEquals(setOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt").size, ModelCatalog.VI_30M.files.size)
    }
}
