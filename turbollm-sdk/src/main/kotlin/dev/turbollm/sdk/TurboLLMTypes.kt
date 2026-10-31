package dev.turbollm.sdk

import dev.turbollm.sdk.api.TurboLLMException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface ModelRef {
    data class Installed(val id: String) : ModelRef

    /** Requires `TurboLLMConfig.allowLoadByPath`. */
    data class File(val path: String) : ModelRef
}

data class LoadOptions(
    /** null: min(4096, the model's trained context). */
    val contextSize: Int? = null,
    /** null: the performance-core count, clamped to 1..8. */
    val threads: Int? = null,
    val backend: Backend = Backend.AUTO,
)

/** llama.cpp in M1 is CPU only: GPU falls back to CPU, with a note in [LoadedModel.notes]. */
enum class Backend { AUTO, CPU, GPU }

data class LoadedModel(
    val id: String,
    val path: String,
    val format: ModelFormat,
    val engine: EngineKind,
    val contextSize: Int,
    val backend: Backend,
    val notes: List<String>,
)

/** Wire names match the TurboLLM daemon's model formats. */
@Serializable
enum class ModelFormat {
    @SerialName("gguf") GGUF,
    @SerialName("litertlm") LITERTLM,
}

/** Wire names match the TurboLLM daemon's engine kinds. */
@Serializable
enum class EngineKind {
    @SerialName("llama.cpp") LLAMA_CPP,
    @SerialName("litert-lm") LITERT_LM,
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val model: ModelRef) : EngineState
    data class Ready(val model: LoadedModel, val busy: Boolean, val queued: Int) : EngineState
    data class Failed(val model: ModelRef, val error: TurboLLMException) : EngineState
}

/** [CANCELLED] never reaches the wire: a cancelled request has no client left to answer. */
@Serializable
enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("cancelled") CANCELLED,
}

/** A Hugging Face failure. Kotlin API only: it is never sent over HTTP in M1. */
sealed class HfError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthRequired(message: String = "Hugging Face needs a valid token for this repository.") : HfError(message)
    class Gated(message: String = "This repository is gated; accept its license on huggingface.co.") : HfError(message)
    class NotFound(message: String = "Not found on Hugging Face.") : HfError(message)
    class Unreachable(message: String, cause: Throwable? = null) : HfError(message, cause)
}
