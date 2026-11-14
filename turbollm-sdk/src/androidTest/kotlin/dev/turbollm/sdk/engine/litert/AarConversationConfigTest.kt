package dev.turbollm.sdk.engine.litert

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a [LiteRtPrompt]'s thinking switch reaches LiteRT-LM 0.19.0's `ConversationConfig` (QA B-38). It runs on the
 * device because the AAR's classes target Java 21, newer than the JVM the unit tests run on. No model needed.
 */
@RunWith(AndroidJUnit4::class)
class AarConversationConfigTest {

    @Test
    fun aPromptWithThinkingOffCreatesAConversationWithThinkingOff() {
        val thinking = conversationConfigOf(promptWith(enableThinking = false)).thinkingConfig

        assertEquals(false, thinking?.enableThinking)
    }

    @Test
    fun aPromptWithThinkingOnLeavesTheModelsOwnThinkingDefault() {
        assertNull(conversationConfigOf(promptWith(enableThinking = true)).thinkingConfig)
    }

    private fun promptWith(enableThinking: Boolean) = LiteRtPrompt(
        systemInstruction = null,
        history = emptyList(),
        userTurn = "Hi",
        sampler = LiteRtSampler(topK = 40, topP = 0.95, temperature = 0.8, seed = 0),
        maxOutputTokens = null,
        enableThinking = enableThinking,
    )
}
