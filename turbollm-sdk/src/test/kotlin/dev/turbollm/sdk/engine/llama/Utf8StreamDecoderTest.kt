package dev.turbollm.sdk.engine.llama

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamDecoderTest {

    @Test
    fun asciiPassesStraightThrough() {
        assertEquals("hello", Utf8StreamDecoder().decode("hello".toByteArray()))
    }

    @Test
    fun aTwoByteCharacterSplitAtEveryBoundaryReassemblesExactly() {
        assertEverySplitReassembles("aé")
    }

    @Test
    fun aThreeByteCharacterSplitAtEveryBoundaryReassemblesExactly() {
        assertEverySplitReassembles("a€b")
    }

    @Test
    fun aFourByteCharacterSplitAtEveryBoundaryReassemblesExactly() {
        assertEverySplitReassembles("x😀y")
    }

    @Test
    fun aCharacterFedOneByteAtATimeAppearsOnlyWhenComplete() {
        val decoder = Utf8StreamDecoder()
        val bytes = "😀".toByteArray()

        val pieces = bytes.map { decoder.decode(byteArrayOf(it)) }

        assertEquals(listOf("", "", "", "😀"), pieces)
    }

    @Test
    fun anInvalidByteBecomesAReplacementCharacter() {
        val bytes = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())

        assertEquals("a\uFFFDb", Utf8StreamDecoder().decode(bytes))
    }

    @Test
    fun aLeadByteFollowedByAsciiBecomesAReplacementCharacterAndKeepsTheAscii() {
        assertEquals("\uFFFDA", Utf8StreamDecoder().decode(byteArrayOf(0xE2.toByte(), 'A'.code.toByte())))
    }

    @Test
    fun anIncompleteSequenceLeftAtTheEndIsFlushedAsAReplacementCharacter() {
        val decoder = Utf8StreamDecoder()

        decoder.decode("€".toByteArray().copyOfRange(0, 2))

        assertEquals("\uFFFD", decoder.flush())
    }

    @Test
    fun flushingAfterCompleteTextGivesNothingAndTheDecoderStartsClean() {
        val decoder = Utf8StreamDecoder()
        decoder.decode(byteArrayOf(0xF0.toByte()))
        decoder.flush()

        assertEquals("", decoder.flush())
        assertEquals("ok", decoder.decode("ok".toByteArray()))
    }

    private fun assertEverySplitReassembles(text: String) {
        val bytes = text.toByteArray()
        for (cut in 0..bytes.size) {
            val decoder = Utf8StreamDecoder()

            val joined = decoder.decode(bytes.copyOfRange(0, cut)) + decoder.decode(bytes.copyOfRange(cut, bytes.size))

            assertEquals("split at byte $cut", text, joined + decoder.flush())
        }
    }
}
