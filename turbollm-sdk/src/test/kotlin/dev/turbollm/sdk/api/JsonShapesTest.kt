package dev.turbollm.sdk.api

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.LoadOptions
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.internal.sdkJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JsonShapesTest {

    @Test
    fun requestWithStringContentRoundTripsAsAString() {
        val wire = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""

        val request = decodeRequest(wire)

        assertEquals(MessageContent.Text("hi"), request.messages.single().content)
        assertEquals(ChatRole.USER, request.messages.single().role)
        assertEquals(parse(wire).withStreamDefault(), parse(encodeRequest(request)))
    }

    @Test
    fun requestWithContentPartsRoundTripsAsAnArray() {
        val wire = """{"model":"m","messages":[{"role":"user","content":[{"type":"text","text":"a"},""" +
            """{"type":"text","text":"b"}]}]}"""

        val request = decodeRequest(wire)

        val expected = MessageContent.Parts(listOf(ContentPart.Text("a"), ContentPart.Text("b")))
        assertEquals(expected, request.messages.single().content)
        assertEquals(parse(wire).withStreamDefault(), parse(encodeRequest(request)))
    }

    @Test
    fun nonTextContentPartIsKeptAsUnsupportedSoItCanBeRefused() {
        val wire = """{"model":"m","messages":[{"role":"user","content":[""" +
            """{"type":"image_url","image_url":{"url":"data:x"}}]}]}"""

        val parts = decodeRequest(wire).messages.single().content as MessageContent.Parts

        assertEquals(listOf(ContentPart.Unsupported("image_url")), parts.parts)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val wire = """{"model":"m","messages":[{"role":"system","content":"s","name":"x"}],""" +
            """"user":"u","foo":{"bar":1}}"""

        val request = decodeRequest(wire)

        assertEquals("m", request.model)
        assertEquals(ChatRole.SYSTEM, request.messages.single().role)
    }

    @Test
    fun samplerFieldsDecodeFromOpenAiNames() {
        val wire = """{"model":"m","messages":[],"stream":true,"stream_options":{"include_usage":true},""" +
            """"temperature":0.7,"top_p":0.9,"top_k":40,"max_tokens":100,"max_completion_tokens":50,"seed":7}"""

        val request = decodeRequest(wire)

        assertEquals(true, request.stream)
        assertEquals(true, request.streamOptions?.includeUsage)
        assertEquals(0.7f, request.temperature)
        assertEquals(0.9f, request.topP)
        assertEquals(40, request.topK)
        assertEquals(100, request.maxTokens)
        assertEquals(50, request.maxCompletionTokens)
        assertEquals(7L, request.seed)
    }

    @Test
    fun stopAcceptsASingleStringOrAList() {
        val single = decodeRequest("""{"model":"m","messages":[],"stop":"END"}""")
        val many = decodeRequest("""{"model":"m","messages":[],"stop":["a","b"]}""")

        assertEquals(listOf("END"), single.stop)
        assertEquals(listOf("a", "b"), many.stop)
    }

    @Test
    fun refusedFieldsAreCapturedForDetection() {
        val wire = """{"model":"m","messages":[],"tools":[{"type":"function"}],"tool_choice":"auto",""" +
            """"functions":[],"response_format":{"type":"json_object"},"n":2,"logprobs":true,"top_logprobs":3}"""

        val request = decodeRequest(wire)

        assertNotNull(request.tools)
        assertEquals(JsonPrimitive("auto"), request.toolChoice)
        assertNotNull(request.functions)
        assertNotNull(request.responseFormat)
        assertEquals(JsonPrimitive(2), request.n)
        assertEquals(JsonPrimitive(true), request.logprobs)
        assertEquals(JsonPrimitive(3), request.topLogprobs)
    }

    @Test
    fun absentRefusedFieldsStayNull() {
        val request = decodeRequest("""{"model":"m","messages":[]}""")

        assertNull(request.tools)
        assertNull(request.responseFormat)
    }

    @Test
    fun chatTemplateKwargsAndThinkingBudgetRoundTrip() {
        val wire = """{"model":"m","messages":[],"stream":false,""" +
            """"chat_template_kwargs":{"enable_thinking":false},"thinking_budget_tokens":512}"""

        val request = decodeRequest(wire)

        assertEquals(buildJsonObject { put("enable_thinking", false) }, request.chatTemplateKwargs)
        assertEquals(512, request.thinkingBudgetTokens)
        assertEquals(parse(wire), parse(encodeRequest(request)))
    }

    @Test
    fun chatCompletionSerialisesWithItsObjectTypeUsageAndTimings() {
        val completion = ChatCompletion(
            id = "chatcmpl-1",
            created = 1730246400,
            model = "m",
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatCompletionMessage(content = "hello", reasoningContent = "think"),
                    finishReason = FinishReason.STOP,
                ),
            ),
            usage = Usage(promptTokens = 3, completionTokens = 2, totalTokens = 5),
            timings = sampleTimings(),
        )

        val expected = """{"id":"chatcmpl-1","object":"chat.completion","created":1730246400,"model":"m",""" +
            """"choices":[{"index":0,"message":{"role":"assistant","content":"hello",""" +
            """"reasoning_content":"think"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5},"timings":$SAMPLE_TIMINGS}"""
        assertEquals(parse(expected), parse(sdkJson.encodeToString(ChatCompletion.serializer(), completion)))
    }

    @Test
    fun chatCompletionChunkSerialisesWithItsObjectTypeAndOmitsAbsentFields() {
        val chunk = ChatCompletionChunk(
            id = "chatcmpl-1",
            created = 1730246400,
            model = "m",
            choices = listOf(ChunkChoice(index = 0, delta = ChunkDelta(role = ChatRole.ASSISTANT))),
        )

        val expected = """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1730246400,"model":"m",""" +
            """"choices":[{"index":0,"delta":{"role":"assistant"}}]}"""
        assertEquals(parse(expected), parse(sdkJson.encodeToString(ChatCompletionChunk.serializer(), chunk)))
    }

    @Test
    fun finalChunkCarriesFinishReasonUsageAndTimings() {
        val chunk = ChatCompletionChunk(
            id = "c",
            created = 1,
            model = "m",
            choices = listOf(ChunkChoice(index = 0, delta = ChunkDelta(content = "x"), finishReason = FinishReason.LENGTH)),
            usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            timings = sampleTimings(),
        )

        val expected = """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m",""" +
            """"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":"length"}],""" +
            """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2},"timings":$SAMPLE_TIMINGS}"""
        assertEquals(parse(expected), parse(sdkJson.encodeToString(ChatCompletionChunk.serializer(), chunk)))
    }

    @Test
    fun modelListIsOwnedByTheSdk() {
        val list = ModelList(
            data = listOf(
                ModelEntry(
                    id = "org/repo/file",
                    created = 1730246400,
                    meta = ModelMeta(
                        format = ModelFormat.GGUF,
                        engine = EngineKind.LLAMA_CPP,
                        sizeBytes = 1107000000,
                        loaded = true,
                    ),
                ),
            ),
        )

        val expected = """{"object":"list","data":[{"id":"org/repo/file","object":"model","created":1730246400,""" +
            """"owned_by":"turbollm-sdk","meta":{"format":"gguf","engine":"llama.cpp","size_bytes":1107000000,""" +
            """"loaded":true}}]}"""
        assertEquals(parse(expected), parse(sdkJson.encodeToString(ModelList.serializer(), list)))
    }

    @Test
    fun liteRtModelsUseTheDaemonFormatAndEngineNames() {
        val meta = ModelMeta(format = ModelFormat.LITERTLM, engine = EngineKind.LITERT_LM, sizeBytes = 1, loaded = false)

        val expected = """{"format":"litertlm","engine":"litert-lm","size_bytes":1,"loaded":false}"""
        assertEquals(parse(expected), parse(sdkJson.encodeToString(ModelMeta.serializer(), meta)))
    }

    @Test
    fun loadOptionsNameNoEngineByDefault() {
        assertNull(LoadOptions().engine)
    }

    private fun decodeRequest(wire: String) = sdkJson.decodeFromString(ChatCompletionRequest.serializer(), wire)

    private fun encodeRequest(request: ChatCompletionRequest) =
        sdkJson.encodeToString(ChatCompletionRequest.serializer(), request)

    private fun parse(json: String): JsonElement = sdkJson.parseToJsonElement(json)

    private fun JsonElement.withStreamDefault(): JsonElement =
        JsonObject((this as JsonObject) + ("stream" to JsonPrimitive(false)))

    private fun sampleTimings() = Timings(
        promptN = 3,
        promptMs = 12.5,
        predictedN = 2,
        predictedMs = 40.0,
        predictedPerSecond = 50.0,
    )

    private companion object {
        const val SAMPLE_TIMINGS = """{"prompt_n":3,"prompt_ms":12.5,"predicted_n":2,"predicted_ms":40.0,""" +
            """"predicted_per_second":50.0}"""
    }
}
