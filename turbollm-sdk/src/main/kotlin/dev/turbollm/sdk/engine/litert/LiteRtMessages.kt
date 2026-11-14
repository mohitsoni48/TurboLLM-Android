package dev.turbollm.sdk.engine.litert

import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.ContentPart
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.GenerationParams

private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_TEMPERATURE = 0.8
private const val DEFAULT_SEED = 0
private const val SYSTEM_SEPARATOR = "\n\n"

internal enum class LiteRtRole { USER, MODEL }

internal data class LiteRtTurn(val role: LiteRtRole, val text: String)

internal data class LiteRtSampler(val topK: Int, val topP: Double, val temperature: Double, val seed: Int)

/**
 * One request as LiteRT-LM takes it (architecture §2.7): a fresh conversation seeded with [systemInstruction]
 * and [history], to which [userTurn] is then sent. [enableThinking] false asks a thinking model not to think.
 */
internal data class LiteRtPrompt(
    val systemInstruction: String?,
    val history: List<LiteRtTurn>,
    val userTurn: String,
    val sampler: LiteRtSampler,
    val maxOutputTokens: Int?,
    val enableThinking: Boolean,
) {
    companion object {
        /** Throws `invalid_last_message` unless the final message is the user's: that is the turn LiteRT answers. */
        fun of(messages: List<ChatMessage>, params: GenerationParams): LiteRtPrompt {
            val userTurn = messages.lastOrNull()?.takeIf { it.role == ChatRole.USER } ?: throw lastTurnNotFromUser()
            val earlier = messages.dropLast(1)
            return LiteRtPrompt(
                systemInstruction = systemInstructionOf(earlier),
                history = earlier.mapNotNull(::turnOf),
                userTurn = userTurn.text(),
                sampler = samplerOf(params),
                maxOutputTokens = params.maxTokens,
                enableThinking = params.enableThinking,
            )
        }
    }
}

private fun lastTurnNotFromUser() = TurboLLMException.InvalidRequest(
    param = "messages",
    message = "The last message must come from the user for a LiteRT-LM model.",
    reason = TurboLLMException.InvalidRequest.Reason.INVALID_LAST_MESSAGE,
)

private fun systemInstructionOf(messages: List<ChatMessage>): String? =
    messages.filter { it.role == ChatRole.SYSTEM }
        .joinToString(SYSTEM_SEPARATOR) { it.text() }
        .ifEmpty { null }

private fun turnOf(message: ChatMessage): LiteRtTurn? = when (message.role) {
    ChatRole.SYSTEM -> null
    ChatRole.USER -> LiteRtTurn(LiteRtRole.USER, message.text())
    ChatRole.ASSISTANT -> LiteRtTurn(LiteRtRole.MODEL, message.text())
}

private fun samplerOf(params: GenerationParams) = LiteRtSampler(
    topK = params.topK ?: DEFAULT_TOP_K,
    topP = params.topP?.toDouble() ?: DEFAULT_TOP_P,
    temperature = params.temperature?.toDouble() ?: DEFAULT_TEMPERATURE,
    seed = params.seed?.toInt() ?: DEFAULT_SEED,
)

/** The chat layer has already refused every non-text part, so only text parts remain to join. */
private fun ChatMessage.text(): String = when (val body = content) {
    is MessageContent.Text -> body.text
    is MessageContent.Parts -> body.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
}
