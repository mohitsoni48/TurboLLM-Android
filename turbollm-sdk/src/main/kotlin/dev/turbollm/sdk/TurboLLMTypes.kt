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
    /** null: the first available engine that reads the file's format. */
    val engine: EngineId? = null,
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
    val engineId: EngineId,
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

/** A stable id for one engine this SDK build hosts. Wire value = [value]. Open, so a new engine is one more value. */
@JvmInline
value class EngineId(val value: String) {
    companion object {
        val LLAMA_CPP_CPU = EngineId("llama.cpp-cpu")
        val LITERT_LM = EngineId("litert-lm")
    }
}

data class EngineInfo(
    val id: EngineId,
    /** For people: "llama.cpp (CPU)", "LiteRT-LM". */
    val name: String,
    /** The runtime family. Two ids can share a kind. */
    val kind: EngineKind,
    val formats: Set<ModelFormat>,
    /** What it can run on: CPU and/or GPU. Never AUTO. */
    val backends: Set<Backend>,
    val availability: EngineAvailability,
)

sealed interface EngineAvailability {
    data object Available : EngineAvailability
    data class Unavailable(val reason: String) : EngineAvailability
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
