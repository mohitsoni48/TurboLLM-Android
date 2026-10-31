package dev.turbollm.sdk.service

import dev.turbollm.sdk.EngineState

/** The line [TurboLLMService]'s default notification shows for the core's [state]. */
internal fun notificationText(state: EngineState): String = when (state) {
    EngineState.Idle -> "Idle"
    is EngineState.Loading -> "Loading a model"
    is EngineState.Ready -> "Model ready · ${state.model.id}"
    is EngineState.Failed -> "The model failed to load"
}
