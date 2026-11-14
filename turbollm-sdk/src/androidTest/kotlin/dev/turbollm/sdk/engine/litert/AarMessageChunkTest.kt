package dev.turbollm.sdk.engine.litert

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a LiteRT-LM 0.19.0 streamed [Message] becomes the [LiteRtChunk] the engine turns into events. It runs on
 * the device because the AAR's classes target Java 21, newer than the JVM the unit tests run on. No model needed.
 */
@RunWith(AndroidJUnit4::class)
class AarMessageChunkTest {

    @Test
    fun aThinkingChunkCarriesItsThoughtChannelAndNoText() {
        val thinking = Message.model(Contents.of(emptyList()), emptyList(), mapOf("thought" to "Okay,"))

        assertEquals(LiteRtChunk(text = "", thought = "Okay,"), thinking.toChunk())
    }

    @Test
    fun anAnswerChunkCarriesItsContentsAsText() {
        assertEquals(LiteRtChunk(text = "Yes.", thought = ""), Message.model("Yes.").toChunk())
    }

    @Test
    fun aChunkWithBothKeepsTheThoughtAndTheText() {
        val both = Message.model(Contents.of("Yes."), emptyList(), mapOf("thought" to "Done."))

        assertEquals(LiteRtChunk(text = "Yes.", thought = "Done."), both.toChunk())
    }
}
