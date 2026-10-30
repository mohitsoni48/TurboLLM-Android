package dev.turbollm.sdk.api

import dev.turbollm.sdk.internal.sdkJson
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorJsonGoldenTest {

    @Test
    fun everyErrorEncodesToItsGoldenFileByteForByte() {
        httpErrorRows.forEach { row ->
            assertEquals("golden ${row.code}", golden(row.code), encode(row.error))
        }
    }

    @Test
    fun everyErrorCarriesItsHttpStatusTypeAndCode() {
        httpErrorRows.forEach { row ->
            assertEquals("status ${row.code}", row.httpStatus, row.error.httpStatus)
            assertEquals("type ${row.code}", row.type, row.error.type)
            assertEquals("code ${row.code}", row.code, row.error.code)
        }
    }

    @Test
    fun serverBusyAsksTheClientToRetryAfterOneSecond() {
        assertEquals(1, TurboLLMException.ServerBusy().retryAfterSeconds)
    }

    @Test
    fun invalidRequestReasonsMapToTheirCodes() {
        val codes = TurboLLMException.InvalidRequest.Reason.entries.map { reason ->
            TurboLLMException.InvalidRequest(param = "p", message = "m", reason = reason).code
        }

        assertEquals(
            listOf("invalid_request", "unsupported_parameter", "unsupported_content", "invalid_last_message"),
            codes,
        )
    }

    @Test
    fun invalidRequestNamesTheOffendingParameter() {
        val error = TurboLLMException.InvalidRequest(
            param = "tools",
            message = "tools is not supported",
            reason = TurboLLMException.InvalidRequest.Reason.UNSUPPORTED_PARAMETER,
        )

        val expected = """{"error":{"message":"tools is not supported","type":"invalid_request_error",""" +
            """"param":"tools","code":"unsupported_parameter"}}"""
        assertEquals(expected, encode(error))
    }

    @Test
    fun loadFailureKeepsItsCause() {
        val cause = IllegalStateException("bad magic")

        assertEquals(cause, TurboLLMException.ModelLoadFailed("load failed", cause).cause)
    }

    private fun encode(error: TurboLLMException): String =
        sdkJson.encodeToString(ErrorBody.serializer(), error.toErrorBody())

    private fun golden(code: String): String {
        val resource = checkNotNull(javaClass.getResourceAsStream("/errors/$code.json")) { "no golden for $code" }
        return resource.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private data class Row(val error: TurboLLMException, val httpStatus: Int, val type: String, val code: String)

    private val httpErrorRows = listOf(
        Row(
            TurboLLMException.InvalidRequest(param = "messages", message = "messages must not be empty"),
            400, "invalid_request_error", "invalid_request",
        ),
        Row(TurboLLMException.ContextLengthExceeded(), 400, "invalid_request_error", "context_length_exceeded"),
        Row(TurboLLMException.TemplateUnsupported(), 400, "invalid_request_error", "chat_template_unsupported"),
        Row(TurboLLMException.Unauthorized(), 401, "authentication_error", "invalid_api_key"),
        Row(TurboLLMException.LoadByPathDisabled(), 403, "permission_error", "load_by_path_disabled"),
        Row(TurboLLMException.ModelNotFound(), 404, "invalid_request_error", "model_not_found"),
        Row(TurboLLMException.ModelNotLoaded(), 409, "invalid_request_error", "model_not_loaded"),
        Row(TurboLLMException.LoadInProgress(), 409, "invalid_request_error", "load_in_progress"),
        Row(TurboLLMException.ServerBusy(), 429, "rate_limit_error", "server_busy"),
        Row(TurboLLMException.ModelLoadFailed("Model load failed."), 500, "server_error", "model_load_failed"),
        Row(TurboLLMException.EngineFailure("The engine failed."), 500, "server_error", "engine_error"),
        Row(TurboLLMException.EngineUnavailable(), 501, "server_error", "engine_unavailable"),
        Row(TurboLLMException.InsufficientMemory(), 507, "server_error", "insufficient_memory"),
    )
}
