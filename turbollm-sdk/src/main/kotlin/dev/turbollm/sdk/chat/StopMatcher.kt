package dev.turbollm.sdk.chat

private const val MAX_STOP_SEQUENCES = 4

/**
 * Applies OpenAI stop sequences to a streamed content text, the same way for every engine. Text that could
 * still be the start of a stop is held back until the next chunk decides it; at the first complete stop the
 * text is cut before it. Stops never apply to reasoning text (llama-server behaviour).
 */
internal class StopMatcher(stops: List<String>) {

    private val stops = stops.filter { it.isNotEmpty() }
    private var heldBack = ""

    init {
        require(stops.size <= MAX_STOP_SEQUENCES) { "at most $MAX_STOP_SEQUENCES stop sequences" }
    }

    /** Releases the part of [chunk] (plus anything held back) that can no longer belong to a stop. */
    fun feed(chunk: String): StopOutcome {
        val pending = heldBack + chunk
        val stopStart = earliestEndingStopStart(pending)
        if (stopStart != null) {
            heldBack = ""
            return StopOutcome(pending.substring(0, stopStart), matched = true)
        }
        val releasable = pending.length - unsafeTailLength(pending)
        heldBack = pending.substring(releasable)
        return StopOutcome(pending.substring(0, releasable), matched = false)
    }

    /** End of stream with no stop matched: whatever was held back is ordinary text after all. */
    fun flush(): String = heldBack.also { heldBack = "" }

    private fun earliestEndingStopStart(text: String): Int? {
        for (end in 1..text.length) {
            val starts = stops.filter { text.endsWithAt(it, end) }.map { end - it.length }
            if (starts.isNotEmpty()) return starts.min()
        }
        return null
    }

    private fun unsafeTailLength(text: String): Int {
        val stopPrefixLength = longestSuffixThatStartsAStop(text)
        val splitsASurrogatePair = stopPrefixLength == 0 && text.lastOrNull()?.isHighSurrogate() == true
        return if (splitsASurrogatePair) 1 else stopPrefixLength
    }

    private fun longestSuffixThatStartsAStop(text: String): Int {
        val longestCandidate = minOf(text.length, (stops.maxOfOrNull { it.length } ?: 1) - 1)
        return (longestCandidate downTo 1).firstOrNull { length ->
            val suffix = text.substring(text.length - length)
            stops.any { it.length > length && it.startsWith(suffix) }
        } ?: 0
    }

    private fun String.endsWithAt(stop: String, end: Int): Boolean =
        end >= stop.length && regionMatches(end - stop.length, stop, 0, stop.length)
}

internal data class StopOutcome(val text: String, val matched: Boolean)
