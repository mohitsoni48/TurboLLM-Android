package dev.turbollm.sdk.chat

import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ContentPart
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.api.TurboLLMException.InvalidRequest
import dev.turbollm.sdk.engine.GenerationParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

private const val MAX_STOP_SEQUENCES = 4
private const val ENABLE_THINKING = "enable_thinking"

/** A request that passed validation, in the terms the engine takes. */
internal data class ValidChatRequest(
    val messages: List<ChatMessage>,
    val params: GenerationParams,
    val stops: List<String>,
)

/**
 * Refuses what M1 cannot honour, by name, instead of ignoring it (architecture §4.2): tool calling, structured
 * output, `n > 1`, logprobs and non-text content. Out-of-range sampler values are invalid requests.
 */
internal fun ChatCompletionRequest.validated(): ValidChatRequest {
    firstUnsupportedParameter()?.let { field -> throw unsupportedParameter(field) }
    refuseNonTextContent()
    checkRanges()
    return ValidChatRequest(messages, generationParams(), stop.orEmpty())
}

private fun ChatCompletionRequest.firstUnsupportedParameter(): String? = when {
    tools.isPresent() -> "tools"
    toolChoice.isPresent() -> "tool_choice"
    functions.isPresent() -> "functions"
    responseFormat.isPresent() && !responseFormat.isPlainTextFormat() -> "response_format"
    n.isPresent() && !n.isSingleChoice() -> "n"
    logprobs.isPresent() && !logprobs.isFalse() -> "logprobs"
    topLogprobs.isPresent() -> "top_logprobs"
    else -> null
}

private fun unsupportedParameter(field: String) =
    InvalidRequest(field, "The parameter '$field' is not supported.", InvalidRequest.Reason.UNSUPPORTED_PARAMETER)

private fun ChatCompletionRequest.refuseNonTextContent() {
    messages.forEachIndexed { index, message ->
        val unsupported = message.contentParts().filterIsInstance<ContentPart.Unsupported>().firstOrNull()
        if (unsupported != null) {
            throw InvalidRequest(
                "messages[$index].content",
                "Content parts of type '${unsupported.type}' are not supported; only text is.",
                InvalidRequest.Reason.UNSUPPORTED_CONTENT,
            )
        }
    }
}

private fun ChatMessage.contentParts(): List<ContentPart> = (content as? MessageContent.Parts)?.parts.orEmpty()

private fun ChatCompletionRequest.checkRanges() {
    requireValid(messages.isNotEmpty(), "messages", "messages must not be empty.")
    requireValid(temperature == null || temperature in 0f..2f, "temperature", "temperature must be between 0 and 2.")
    requireValid(topP == null || topP in 0f..1f, "top_p", "top_p must be between 0 and 1.")
    requireValid(topK == null || topK >= 0, "top_k", "top_k must be 0 or more.")
    requireValid(maxTokens == null || maxTokens >= 1, "max_tokens", "max_tokens must be 1 or more.")
    requireValid(
        maxCompletionTokens == null || maxCompletionTokens >= 1,
        "max_completion_tokens",
        "max_completion_tokens must be 1 or more.",
    )
    requireValid(stop.orEmpty().size <= MAX_STOP_SEQUENCES, "stop", "stop takes at most $MAX_STOP_SEQUENCES sequences.")
}

private fun requireValid(valid: Boolean, param: String, message: String) {
    if (!valid) throw InvalidRequest(param, message)
}

/** ADR-447 point 7: when both token limits are set, the smaller wins. */
private fun ChatCompletionRequest.generationParams() = GenerationParams(
    maxTokens = listOfNotNull(maxTokens, maxCompletionTokens).minOrNull(),
    temperature = temperature,
    topP = topP,
    topK = topK,
    seed = seed,
    enableThinking = !turnsThinkingOff(),
    templateKwargs = templateKwargsForTheEngine(),
)

/** A positive `thinking_budget_tokens` is not enforced in M1 (recorded debt); only 0 turns thinking off. */
private fun ChatCompletionRequest.turnsThinkingOff(): Boolean =
    thinkingBudgetTokens == 0 || (chatTemplateKwargs?.get(ENABLE_THINKING) as? JsonPrimitive)?.booleanOrNull == false

private fun ChatCompletionRequest.templateKwargsForTheEngine(): Map<String, String> =
    chatTemplateKwargs.orEmpty()
        .filterKeys { it != ENABLE_THINKING }
        .mapValues { (_, value) -> value.toString() }

private fun JsonElement?.isPresent(): Boolean = this != null && this !is JsonNull

private fun JsonElement?.isPlainTextFormat(): Boolean =
    ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.let { it.isString && it.content == "text" } == true

private fun JsonElement?.isSingleChoice(): Boolean = (this as? JsonPrimitive)?.intOrNull == 1

private fun JsonElement?.isFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == false
