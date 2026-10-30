package dev.turbollm.sdk.engine

import dev.turbollm.sdk.FinishReason

internal sealed interface EngineEvent {
    data class Text(val text: String) : EngineEvent
    data class Reasoning(val text: String) : EngineEvent
    data class Done(
        val reason: FinishReason,
        val promptTokens: Int,
        val completionTokens: Int,
        val promptMs: Double,
        val genMs: Double,
    ) : EngineEvent
}
