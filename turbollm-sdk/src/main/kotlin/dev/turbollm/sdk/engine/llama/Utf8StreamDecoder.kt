package dev.turbollm.sdk.engine.llama

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Turns token bytes into text without splitting a code point across tokens.
 * An incomplete sequence is held until its next bytes arrive; an invalid byte becomes U+FFFD.
 * Not thread-safe: one decoder per generation, used on the engine thread.
 */
internal class Utf8StreamDecoder {

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var heldBack = ByteArray(0)

    /** Text for every complete character so far; the bytes of a trailing partial character are kept. */
    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(heldBack + bytes)
        val text = decodeFrom(input, endOfInput = false)
        heldBack = ByteArray(input.remaining()).also { input.get(it) }
        return text
    }

    /** Ends the stream: a held-back partial character becomes U+FFFD, and the decoder starts clean. */
    fun flush(): String {
        val text = decodeFrom(ByteBuffer.wrap(heldBack), endOfInput = true)
        heldBack = ByteArray(0)
        decoder.reset()
        return text
    }

    private fun decodeFrom(input: ByteBuffer, endOfInput: Boolean): String {
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, endOfInput)
        if (endOfInput) decoder.flush(output)
        return output.flip().toString()
    }
}
