package dev.turbollm.sdk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StopMatcherTest {

    @Test
    fun everySplitOfTheTextCutsAtTheStopAndReportsTheMatch() {
        val text = "hello STOP world"

        allSplits(text).forEach { chunks ->
            val run = run(listOf("STOP"), chunks)

            assertEquals("chunks $chunks", "hello ", run.released)
            assertTrue("chunks $chunks", run.matched)
        }
    }

    @Test
    fun overlappingStopsResolveToTheEarliestEnd() {
        val cases = listOf(
            Case(stops = listOf("ab", "abc"), chunks = listOf("xabc"), released = "x"),
            Case(stops = listOf("abc", "ab"), chunks = listOf("xa", "bc"), released = "x"),
            Case(stops = listOf("bcd", "abcde"), chunks = listOf("1abcde"), released = "1a"),
            Case(stops = listOf("bc", "abc"), chunks = listOf("1ab", "c2"), released = "1"),
        )

        cases.forEach { case ->
            val run = run(case.stops, case.chunks)

            assertEquals("case $case", case.released, run.released)
            assertTrue("case $case", run.matched)
        }
    }

    @Test
    fun aHeldBackTailIsFlushedAtEndOfStreamWhenNoStopMatched() {
        val matcher = StopMatcher(listOf("STOP"))

        val released = matcher.feed("hello ST").text

        assertEquals("hello ", released)
        assertEquals("ST", matcher.flush())
    }

    @Test
    fun textWithoutAnyStopPrefixIsReleasedAtOnce() {
        val outcome = StopMatcher(listOf("STOP")).feed("hello")

        assertEquals("hello", outcome.text)
        assertFalse(outcome.matched)
    }

    @Test
    fun withNoStopsEverythingIsReleasedAtOnce() {
        val matcher = StopMatcher(emptyList())

        assertEquals("STOP", matcher.feed("STOP").text)
        assertEquals("", matcher.flush())
    }

    @Test
    fun emptyStopStringsAreIgnored() {
        val outcome = StopMatcher(listOf("")).feed("abc")

        assertEquals("abc", outcome.text)
        assertFalse(outcome.matched)
    }

    @Test
    fun moreThanFourStopsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { StopMatcher(listOf("a", "b", "c", "d", "e")) }
    }

    @Test
    fun aSurrogatePairIsNeverSplitAcrossReleases() {
        val text = "a😀b STOP 😁"
        val random = Random(seed = 7)

        repeat(500) {
            val chunks = randomSplit(text, random)
            val releases = runReleases(listOf("STOP"), chunks)

            releases.forEach { release ->
                assertFalse("lone high surrogate in $chunks", release.lastOrNull()?.isHighSurrogate() == true)
                assertFalse("lone low surrogate in $chunks", release.firstOrNull()?.isLowSurrogate() == true)
            }
            assertEquals("a😀b ", releases.joinToString(""))
        }
    }

    @Test
    fun aStopThatStartsWithAnEmojiIsMatchedAcrossTheSplitPair() {
        val run = run(listOf("😀!"), listOf("ok \uD83D", "\uDE00", "! more"))

        assertEquals("ok ", run.released)
        assertTrue(run.matched)
    }

    private data class Case(val stops: List<String>, val chunks: List<String>, val released: String)

    private data class Run(val released: String, val matched: Boolean)

    private fun run(stops: List<String>, chunks: List<String>): Run {
        val matcher = StopMatcher(stops)
        val released = StringBuilder()
        for (chunk in chunks) {
            val outcome = matcher.feed(chunk)
            released.append(outcome.text)
            if (outcome.matched) return Run(released.toString(), matched = true)
        }
        return Run(released.append(matcher.flush()).toString(), matched = false)
    }

    private fun runReleases(stops: List<String>, chunks: List<String>): List<String> {
        val matcher = StopMatcher(stops)
        val releases = mutableListOf<String>()
        for (chunk in chunks) {
            val outcome = matcher.feed(chunk)
            releases += outcome.text
            if (outcome.matched) return releases.filter { it.isNotEmpty() }
        }
        releases += matcher.flush()
        return releases.filter { it.isNotEmpty() }
    }

    private fun allSplits(text: String): List<List<String>> {
        val cutPoints = text.length - 1
        return (0 until (1 shl cutPoints)).map { mask -> splitAt(text, mask) }
    }

    private fun splitAt(text: String, mask: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        for (cut in 1 until text.length) {
            if (mask and (1 shl (cut - 1)) != 0) {
                chunks += text.substring(start, cut)
                start = cut
            }
        }
        chunks += text.substring(start)
        return chunks
    }

    private fun randomSplit(text: String, random: Random): List<String> =
        splitAt(text, random.nextInt(1 shl (text.length - 1)))
}
