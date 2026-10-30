package dev.turbollm.sdk.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val INVALID_REQUEST_ERROR = "invalid_request_error"
private const val SERVER_ERROR = "server_error"

/**
 * Every failure the SDK reports, from the Kotlin API and over HTTP alike. Each subtype fixes the HTTP status
 * and the OpenAI error `type` and `code` it is sent as (architecture §4.4).
 */
sealed class TurboLLMException(
    message: String,
    val httpStatus: Int,
    val type: String,
    val code: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The request field the error is about, when there is one. */
    open val param: String? = null

    class InvalidRequest(
        override val param: String?,
        message: String,
        reason: Reason = Reason.INVALID_REQUEST,
    ) : TurboLLMException(message, 400, INVALID_REQUEST_ERROR, reason.code) {

        enum class Reason(val code: String) {
            INVALID_REQUEST("invalid_request"),
            UNSUPPORTED_PARAMETER("unsupported_parameter"),
            UNSUPPORTED_CONTENT("unsupported_content"),
            INVALID_LAST_MESSAGE("invalid_last_message"),
        }
    }

    class ContextLengthExceeded(message: String = "The prompt does not fit in the model's context window.") :
        TurboLLMException(message, 400, INVALID_REQUEST_ERROR, "context_length_exceeded")

    class TemplateUnsupported(message: String = "The model's chat template is not supported.") :
        TurboLLMException(message, 400, INVALID_REQUEST_ERROR, "chat_template_unsupported")

    class Unauthorized(message: String = "Invalid or missing API key.") :
        TurboLLMException(message, 401, "authentication_error", "invalid_api_key")

    class LoadByPathDisabled(message: String = "Loading a model by path is disabled.") :
        TurboLLMException(message, 403, "permission_error", "load_by_path_disabled")

    class ModelNotFound(message: String = "The model does not exist.") :
        TurboLLMException(message, 404, INVALID_REQUEST_ERROR, "model_not_found")

    class ModelNotLoaded(message: String = "The model is not loaded.") :
        TurboLLMException(message, 409, INVALID_REQUEST_ERROR, "model_not_loaded")

    class LoadInProgress(message: String = "Another model load is in progress.") :
        TurboLLMException(message, 409, INVALID_REQUEST_ERROR, "load_in_progress")

    class ServerBusy(message: String = "Too many requests are queued; retry shortly.") :
        TurboLLMException(message, 429, "rate_limit_error", "server_busy") {
        val retryAfterSeconds: Int = 1
    }

    class ModelLoadFailed(message: String, cause: Throwable? = null) :
        TurboLLMException(message, 500, SERVER_ERROR, "model_load_failed", cause)

    class EngineFailure(message: String, cause: Throwable? = null) :
        TurboLLMException(message, 500, SERVER_ERROR, "engine_error", cause)

    class EngineUnavailable(message: String = "The engine is not available on this device.") :
        TurboLLMException(message, 501, SERVER_ERROR, "engine_unavailable")

    class InsufficientMemory(message: String = "Not enough memory to load the model.") :
        TurboLLMException(message, 507, SERVER_ERROR, "insufficient_memory")
}

/** The OpenAI error envelope: `{"error":{"message","type","param","code"}}`, with `param` always present. */
@Serializable
internal data class ErrorBody(val error: ErrorDetail)

@Serializable
internal data class ErrorDetail(val message: String, val type: String, val param: JsonElement, val code: String)

internal fun TurboLLMException.toErrorBody(): ErrorBody =
    ErrorBody(ErrorDetail(message = message.orEmpty(), type = type, param = paramOrJsonNull(), code = code))

private fun TurboLLMException.paramOrJsonNull(): JsonElement = param?.let(::JsonPrimitive) ?: JsonNull
