package dev.turbollm.sdk.engine

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineAvailability
import dev.turbollm.sdk.EngineId
import dev.turbollm.sdk.EngineInfo
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.TurboLLMException
import java.util.concurrent.ConcurrentHashMap

/** Creates a fresh engine; throws `TurboLLMException.EngineUnavailable` when it cannot run on this device. */
internal fun interface EngineFactory {
    fun create(): Engine
}

/** One engine compiled into this SDK build. */
internal class EngineSlot(
    val id: EngineId,
    val name: String,
    val kind: EngineKind,
    val formats: Set<ModelFormat>,
    val backends: Set<Backend>,
    val factory: EngineFactory,
    /** null = can run here; else why not. Loads no native code (ABI and cpuinfo checks only). */
    val probe: () -> String?,
)

internal fun llamaCppCpuSlot(factory: EngineFactory, probe: () -> String?) = EngineSlot(
    id = EngineId.LLAMA_CPP_CPU,
    name = "llama.cpp (CPU)",
    kind = EngineKind.LLAMA_CPP,
    formats = setOf(ModelFormat.GGUF),
    backends = setOf(Backend.CPU),
    factory = factory,
    probe = probe,
)

internal fun liteRtLmSlot(factory: EngineFactory, probe: () -> String?) = EngineSlot(
    id = EngineId.LITERT_LM,
    name = "LiteRT-LM",
    kind = EngineKind.LITERT_LM,
    formats = setOf(ModelFormat.LITERTLM),
    backends = setOf(Backend.GPU, Backend.CPU),
    factory = factory,
    probe = probe,
)

/**
 * The engines this SDK build hosts, in preference order: with no engine named, a model runs on the first
 * available engine that reads its format. A named engine is pinned: it never falls back to another.
 */
internal class EngineCatalog(private val slots: List<EngineSlot>) {

    private val runtimeFailures = ConcurrentHashMap<EngineId, String>()

    fun list(): List<EngineInfo> = slots.map { it.describedAs(availabilityOf(it)) }

    /** Validates before any load is accepted. Throws UNKNOWN_ENGINE / ENGINE_FORMAT_MISMATCH / EngineUnavailable. */
    fun resolve(format: ModelFormat, requested: EngineId?): EngineSlot =
        if (requested == null) firstAvailableReaderOf(format) else pinned(requested, format)

    /** The slot a resolved load names. */
    fun slot(id: EngineId): EngineSlot = slots.firstOrNull { it.id == id } ?: throw unknownEngine(id)

    /** Called when an engine failed to start at runtime; it stays unavailable for the rest of the process. */
    fun markUnavailable(id: EngineId, reason: String) {
        runtimeFailures[id] = reason
    }

    private fun firstAvailableReaderOf(format: ModelFormat): EngineSlot {
        val readers = slots.filter { format in it.formats }.map { it to whyUnavailable(it) }
        readers.firstOrNull { (_, why) -> why == null }?.let { (slot, _) -> return slot }
        val (firstReader, why) = checkNotNull(readers.firstOrNull()) { "No engine in this SDK reads $format models." }
        throw firstReader.unavailableBecause(checkNotNull(why))
    }

    private fun pinned(requested: EngineId, format: ModelFormat): EngineSlot {
        val slot = slot(requested)
        if (format !in slot.formats) throw slot.cannotRead(format)
        whyUnavailable(slot)?.let { throw slot.unavailableBecause(it) }
        return slot
    }

    private fun availabilityOf(slot: EngineSlot): EngineAvailability =
        whyUnavailable(slot)?.let(EngineAvailability::Unavailable) ?: EngineAvailability.Available

    private fun whyUnavailable(slot: EngineSlot): String? = runtimeFailures[slot.id] ?: probeReason(slot)

    private fun probeReason(slot: EngineSlot): String? =
        try {
            slot.probe()
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
}

private fun EngineSlot.describedAs(availability: EngineAvailability) =
    EngineInfo(id, name, kind, formats, backends, availability)

private fun EngineSlot.unavailableBecause(reason: String) =
    TurboLLMException.EngineUnavailable(kind, reason, engineId = id)

private fun EngineSlot.cannotRead(format: ModelFormat) = TurboLLMException.InvalidRequest(
    param = ENGINE_PARAM,
    message = "$name (${id.value}) can't read ${format.displayName} models.",
    reason = TurboLLMException.InvalidRequest.Reason.ENGINE_FORMAT_MISMATCH,
)

private fun unknownEngine(requested: EngineId) = TurboLLMException.InvalidRequest(
    param = ENGINE_PARAM,
    message = "This SDK has no engine named ${requested.value}.",
    reason = TurboLLMException.InvalidRequest.Reason.UNKNOWN_ENGINE,
)

private val ModelFormat.displayName: String
    get() = when (this) {
        ModelFormat.GGUF -> "GGUF"
        ModelFormat.LITERTLM -> ".litertlm"
    }

private const val ENGINE_PARAM = "engine"
