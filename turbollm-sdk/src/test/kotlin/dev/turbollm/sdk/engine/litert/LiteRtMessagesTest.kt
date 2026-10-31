package dev.turbollm.sdk.engine.litert

import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.ContentPart
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** How an OpenAI conversation becomes one LiteRT-LM conversation and its final user turn (architecture §2.7). */
class LiteRtMessagesTest {

    @Test
    fun everySystemMessageIsJoinedIntoTheSystemInstruction() {
        val prompt = LiteRtPrompt.of(
            listOf(system("Be brief."), user("Hi"), system("Answer in French."), user("Name a colour.")),
            UNSET,
        )

        assertEquals("Be brief.\n\nAnswer in French.", prompt.systemInstruction)
    }

    @Test
    fun aConversationWithoutASystemMessageHasNoSystemInstruction() {
        assertNull(LiteRtPrompt.of(listOf(user("Hi")), UNSET).systemInstruction)
    }

    @Test
    fun everyTurnBeforeTheFinalUserTurnIsItsHistoryInOrder() {
        val prompt = LiteRtPrompt.of(
            listOf(system("Be brief."), user("First?"), assistant("One."), user("Second?")),
            UNSET,
        )

        assertEquals(
            listOf(LiteRtTurn(LiteRtRole.USER, "First?"), LiteRtTurn(LiteRtRole.MODEL, "One.")),
            prompt.history,
        )
        assertEquals("Second?", prompt.userTurn)
    }

    @Test
    fun theTextPartsOfAMessageAreJoined() {
        val parts = MessageContent.Parts(listOf(ContentPart.Text("Hello, "), ContentPart.Text("world")))

        val prompt = LiteRtPrompt.of(listOf(ChatMessage(ChatRole.USER, parts)), UNSET)

        assertEquals("Hello, world", prompt.userTurn)
    }

    @Test
    fun aFinalTurnFromTheAssistantIsRefusedAsAnInvalidLastMessage() {
        val failure = assertThrows(TurboLLMException.InvalidRequest::class.java) {
            LiteRtPrompt.of(listOf(user("Hi"), assistant("Hello")), UNSET)
        }

        assertEquals("invalid_last_message", failure.code)
        assertEquals(400, failure.httpStatus)
        assertEquals("messages", failure.param)
    }

    @Test
    fun aFinalSystemTurnIsRefusedAsAnInvalidLastMessage() {
        val failure = assertThrows(TurboLLMException.InvalidRequest::class.java) {
            LiteRtPrompt.of(listOf(user("Hi"), system("Be brief.")), UNSET)
        }

        assertEquals("invalid_last_message", failure.code)
    }

    @Test
    fun unsetSamplingParametersUseTheLiteRtDefaults() {
        val prompt = LiteRtPrompt.of(listOf(user("Hi")), UNSET)

        assertEquals(LiteRtSampler(topK = 40, topP = 0.95, temperature = 0.8, seed = 0), prompt.sampler)
    }

    @Test
    fun setSamplingParametersArePassedThrough() {
        val params = UNSET.copy(temperature = 0.25f, topP = 0.5f, topK = 5, seed = 7L)

        val prompt = LiteRtPrompt.of(listOf(user("Hi")), params)

        assertEquals(LiteRtSampler(topK = 5, topP = 0.5, temperature = 0.25, seed = 7), prompt.sampler)
    }

    @Test
    fun theMaxTokensBecomeTheMaxOutputTokens() {
        assertEquals(16, LiteRtPrompt.of(listOf(user("Hi")), UNSET.copy(maxTokens = 16)).maxOutputTokens)
        assertNull(LiteRtPrompt.of(listOf(user("Hi")), UNSET).maxOutputTokens)
    }

    private fun system(text: String) = ChatMessage(ChatRole.SYSTEM, MessageContent.Text(text))
    private fun user(text: String) = ChatMessage(ChatRole.USER, MessageContent.Text(text))
    private fun assistant(text: String) = ChatMessage(ChatRole.ASSISTANT, MessageContent.Text(text))

    private companion object {
        val UNSET = GenerationParams(maxTokens = null, temperature = null, topP = null, topK = null, seed = null)
    }
}
