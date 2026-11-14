package dev.turbollm.sdk.engine

import dev.turbollm.sdk.FinishReason

internal sealed interface EngineEvent {
    /**
     * The prompt is processed. Sent once, before the first [Text] or [Reasoning], so a reply that is cut short
     * still knows its prompt's size and how long it took.
     */
    data class Prefilled(val promptTokens: Int, val promptMs: Double) : EngineEvent
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
