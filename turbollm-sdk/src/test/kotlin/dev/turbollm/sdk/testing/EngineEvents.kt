package dev.turbollm.sdk.testing

import dev.turbollm.sdk.engine.EngineEvent

/** The generated text and reasoning only, without the prefill report and the final `Done`. */
internal fun List<EngineEvent>.pieces(): List<EngineEvent> =
    filter { it is EngineEvent.Text || it is EngineEvent.Reasoning }
