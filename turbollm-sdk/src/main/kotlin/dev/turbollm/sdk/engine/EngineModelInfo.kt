package dev.turbollm.sdk.engine

import dev.turbollm.sdk.Backend

internal data class EngineModelInfo(
    val contextSize: Int,
    val trainedContext: Int?,
    val backend: Backend,
    val notes: List<String>,
)
