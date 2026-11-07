package dev.turbollm.sdk.server

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineAvailability
import dev.turbollm.sdk.EngineId
import dev.turbollm.sdk.EngineInfo
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.LoadOptions
import dev.turbollm.sdk.LoadedModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.TurboLLMException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /sdk/v1/load`: exactly one of [model] (an installed id) and [path] (an absolute file path). */
@Serializable
internal data class LoadRequest(
    val model: String? = null,
    val path: String? = null,
    val contextSize: Int? = null,
    val threads: Int? = null,
    val backend: BackendName = BackendName.AUTO,
    /** An engine id from `GET /sdk/v1/engines`; null routes by the file's format. */
    val engine: String? = null,
) {
    fun modelRef(): ModelRef = when {
        model != null && path == null -> ModelRef.Installed(model)
        path != null && model == null -> ModelRef.File(path)
        else -> throw TurboLLMException.InvalidRequest(param = null, "Name exactly one of 'model' and 'path'.")
    }

    fun options() = LoadOptions(
        contextSize = contextSize,
        threads = threads,
        backend = backend.backend,
        engine = engine?.let(::EngineId),
    )
}

/** The lowercase wire names of [Backend]; the public enum itself stays free of serialization. */
@Serializable
internal enum class BackendName(val backend: Backend) {
    @SerialName("auto") AUTO(Backend.AUTO),
    @SerialName("cpu") CPU(Backend.CPU),
    @SerialName("gpu") GPU(Backend.GPU),
}

@Serializable
internal enum class ControlState {
    @SerialName("idle") IDLE,
    @SerialName("loading") LOADING,
    @SerialName("ready") READY,
    @SerialName("error") ERROR,
}

/** The reply of `POST /sdk/v1/load` (`loading`) and `POST /sdk/v1/unload` (`idle`). */
@Serializable
internal data class StateReply(val state: ControlState)

/** `GET /sdk/v1/status` (architecture §4.3). [model] is present when ready, [error] when a load failed. */
@Serializable
internal data class EngineStatus(
    val state: ControlState,
    val model: StatusModel? = null,
    val busy: Boolean,
    val queued: Int,
    val error: StatusError? = null,
)

@Serializable
internal data class StatusModel(
    val id: String,
    val path: String,
    val format: ModelFormat,
    val engine: EngineKind,
    val engineId: String,
    val contextSize: Int,
    val backend: BackendName,
    val notes: List<String>,
)

@Serializable
internal data class StatusError(val message: String, val code: String)

/** `GET /sdk/v1/engines`: the engines this SDK build hosts, in preference order. */
@Serializable
internal data class EngineListReply(
    @SerialName("object") val objectType: String = "list",
    val data: List<EngineEntry>,
)

/** [reason] is present only when [available] is false. */
@Serializable
internal data class EngineEntry(
    val id: String,
    val name: String,
    val kind: EngineKind,
    val formats: List<ModelFormat>,
    val backends: List<BackendName>,
    val available: Boolean,
    val reason: String? = null,
)

internal fun engineListReply(engines: List<EngineInfo>) = EngineListReply(data = engines.map(EngineInfo::toEngineEntry))

private fun EngineInfo.toEngineEntry() = EngineEntry(
    id = id.value,
    name = name,
    kind = kind,
    formats = formats.toList(),
    backends = backends.map(Backend::wireName),
    available = availability == EngineAvailability.Available,
    reason = (availability as? EngineAvailability.Unavailable)?.reason,
)

/** The status of [state], with [busy] and [queued] read live from the generation gate rather than the snapshot. */
internal fun engineStatus(state: EngineState, busy: Boolean, queued: Int): EngineStatus = when (state) {
    EngineState.Idle -> EngineStatus(ControlState.IDLE, busy = busy, queued = queued)
    is EngineState.Loading -> EngineStatus(ControlState.LOADING, busy = busy, queued = queued)
    is EngineState.Ready -> EngineStatus(ControlState.READY, state.model.toStatusModel(), busy, queued)
    is EngineState.Failed -> EngineStatus(ControlState.ERROR, model = null, busy, queued, state.error.toStatusError())
}

private fun LoadedModel.toStatusModel() = StatusModel(
    id = id,
    path = path,
    format = format,
    engine = engine,
    engineId = engineId.value,
    contextSize = contextSize,
    backend = backend.wireName(),
    notes = notes,
)

private fun Backend.wireName() = BackendName.entries.single { it.backend == this }

private fun TurboLLMException.toStatusError() = StatusError(message = message.orEmpty(), code = code)
