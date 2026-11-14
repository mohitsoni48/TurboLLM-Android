package dev.turbollm.sdk.chat

import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.internal.sdkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatValidationTest {

    @Test
    fun eachRefusedFieldIsRefusedAsAnUnsupportedParameterNamingTheField() {
        val refused = mapOf(
            "tools" to """[{"type":"function","function":{"name":"f"}}]""",
            "tool_choice" to "\"auto\"",
            "functions" to """[{"name":"f"}]""",
            "response_format" to """{"type":"json_object"}""",
            "n" to "2",
            "logprobs" to "true",
            "top_logprobs" to "2",
        )

        refused.forEach { (field, value) ->
            val error = refusal(requestJson(""""$field":$value"""))
            assertEquals(field, "unsupported_parameter", error.code)
            assertEquals(field, error.param)
        }
    }

    @Test
    fun theHarmlessValuesOfRefusableFieldsAreAccepted() {
        val accepted = listOf(
            """"response_format":{"type":"text"}""",
            """"n":1""",
            """"logprobs":false""",
            """"tools":null""",
        )

        accepted.forEach { field -> request(requestJson(field)).validated() }
    }

    @Test
    fun anImagePartIsRefusedAsUnsupportedContent() {
        val json = """{"model":"m","messages":[{"role":"system","content":"s"},{"role":"user","content":[
            {"type":"text","text":"what is this?"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA"}}]}]}"""

        val error = refusal(json)

        assertEquals("unsupported_content", error.code)
        assertEquals("messages[1].content", error.param)
    }

    @Test
    fun anAudioPartIsRefusedAsUnsupportedContent() {
        val json = """{"model":"m","messages":[{"role":"user","content":[
            {"type":"input_audio","input_audio":{"data":"AA","format":"wav"}}]}]}"""

        assertEquals("unsupported_content", refusal(json).code)
    }

    @Test
    fun textPartsAreAccepted() {
        val json = """{"model":"m","messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}"""

        request(json).validated()
    }

    @Test
    fun outOfRangeSamplerValuesAreInvalidRequestsNamingTheField() {
        val outOfRange = mapOf(
            "temperature" to listOf("-0.1", "2.1"),
            "top_p" to listOf("-0.1", "1.1"),
            "top_k" to listOf("-1"),
            "max_tokens" to listOf("0"),
            "max_completion_tokens" to listOf("0"),
            "stop" to listOf("""["a","b","c","d","e"]"""),
        )

        outOfRange.forEach { (field, values) ->
            values.forEach { value ->
                val error = refusal(requestJson(""""$field":$value"""))
                assertEquals("$field=$value", "invalid_request", error.code)
                assertEquals(field, error.param)
            }
        }
    }

    @Test
    fun theRangeEndsAreAccepted() {
        val params = request(requestJson(""""temperature":2,"top_p":1,"top_k":0""")).validated().params
        request(requestJson(""""temperature":0,"stop":["a","b","c","d"]""")).validated()

        assertEquals(2f, params.temperature)
        assertEquals(1f, params.topP)
        assertEquals(0, params.topK)
    }

    @Test
    fun aTopPOfZeroIsAcceptedAsOpenAiDocumentsIt() {
        assertEquals(0f, request(requestJson(""""top_p":0""")).validated().params.topP)
    }

    @Test
    fun anEmptyMessageListIsAnInvalidRequest() {
        val error = refusal("""{"model":"m","messages":[]}""")

        assertEquals("invalid_request", error.code)
        assertEquals("messages", error.param)
    }

    @Test
    fun theSmallerOfMaxTokensAndMaxCompletionTokensWins() {
        assertEquals(50, maxTokensOf(""""max_tokens":100,"max_completion_tokens":50"""))
        assertEquals(40, maxTokensOf(""""max_tokens":40,"max_completion_tokens":50"""))
        assertEquals(70, maxTokensOf(""""max_completion_tokens":70"""))
        assertEquals(30, maxTokensOf(""""max_tokens":30"""))
        assertEquals(null, maxTokensOf(""""seed":1"""))
    }

    @Test
    fun samplerFieldsAndStopsAreCarriedOver() {
        val valid = request(requestJson(""""temperature":0.5,"top_p":0.9,"top_k":40,"seed":7,"stop":"END"""")).validated()

        val expected = GenerationParams(maxTokens = null, temperature = 0.5f, topP = 0.9f, topK = 40, seed = 7)
        assertEquals(expected, valid.params)
        assertEquals(listOf("END"), valid.stops)
    }

    @Test
    fun thinkingIsOnUnlessTheRequestTurnsItOff() {
        val params = request(requestJson(""""seed":1""")).validated().params

        assertTrue(params.enableThinking)
        assertEquals(emptyMap<String, String>(), params.templateKwargs)
    }

    @Test
    fun enableThinkingFalseInTheTemplateKwargsTurnsThinkingOff() {
        val params = paramsOf(""""chat_template_kwargs":{"enable_thinking":false}""")

        assertFalse(params.enableThinking)
        assertFalse("enable_thinking" in params.templateKwargs)
    }

    @Test
    fun enableThinkingTrueInTheTemplateKwargsLeavesThinkingOn() {
        val params = paramsOf(""""chat_template_kwargs":{"enable_thinking":true}""")

        assertTrue(params.enableThinking)
        assertFalse("enable_thinking" in params.templateKwargs)
    }

    @Test
    fun aThinkingBudgetOfZeroTurnsThinkingOff() {
        assertFalse(paramsOf(""""thinking_budget_tokens":0""").enableThinking)
    }

    @Test
    fun aPositiveThinkingBudgetIsNotEnforcedInM1() {
        assertTrue(paramsOf(""""thinking_budget_tokens":256""").enableThinking)
    }

    @Test
    fun everyOtherTemplateKwargIsPassedOnJsonEncoded() {
        val params = paramsOf(
            """"chat_template_kwargs":{"enable_thinking":false,"reasoning_effort":"high","depth":2,"opts":{"a":[1,true]}}""",
        )

        val expected = mapOf(
            "reasoning_effort" to "\"high\"",
            "depth" to "2",
            "opts" to """{"a":[1,true]}""",
        )
        assertEquals(expected, params.templateKwargs)
    }

    private fun maxTokensOf(fields: String): Int? = paramsOf(fields).maxTokens

    private fun paramsOf(fields: String): GenerationParams = request(requestJson(fields)).validated().params

    private fun refusal(json: String): TurboLLMException.InvalidRequest =
        assertThrows(TurboLLMException.InvalidRequest::class.java) { request(json).validated() }

    private fun request(json: String): ChatCompletionRequest =
        sdkJson.decodeFromString(ChatCompletionRequest.serializer(), json)

    private fun requestJson(fields: String) =
        """{"model":"m","messages":[{"role":"user","content":"hi"}],$fields}"""
}
