package dev.turbollm.sdk.server

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.InstalledModel
import dev.turbollm.sdk.LoadedModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.ModelEntry
import dev.turbollm.sdk.api.ModelList
import dev.turbollm.sdk.api.ModelMeta
import dev.turbollm.sdk.models.ModelStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MILLIS_PER_SECOND = 1_000

/**
 * The `GET /v1/models` listing (architecture §4.2): every installed, complete model, `created` = its file's
 * mtime in seconds, and the loaded one marked. A loaded model the listing does not hold (one loaded by path
 * from outside the model root) is listed by its absolute path.
 */
internal class ModelListing(private val store: ModelStore, private val engineState: () -> EngineState) {

    suspend fun current(): ModelList {
        val loaded = (engineState() as? EngineState.Ready)?.model
        val installed = withContext(Dispatchers.IO) { store.list() }.filter { it.complete }
        val entries = installed.map { it.toEntry(loaded = it.path == loaded?.path) }
        return ModelList(data = entries + listOfNotNull(loaded?.takeIf { it.isNotIn(installed) }?.toEntry()))
    }
}

private fun InstalledModel.toEntry(loaded: Boolean) = ModelEntry(
    id = id,
    created = modifiedSeconds(path),
    meta = ModelMeta(format = format, engine = format.engineKind(), sizeBytes = sizeBytes, loaded = loaded),
)

private fun LoadedModel.isNotIn(installed: List<InstalledModel>) = installed.none { it.path == path }

private fun LoadedModel.toEntry() = ModelEntry(
    id = path,
    created = modifiedSeconds(path),
    meta = ModelMeta(format = format, engine = engine, sizeBytes = File(path).length(), loaded = true),
)

private fun modifiedSeconds(path: String): Long = File(path).lastModified() / MILLIS_PER_SECOND

private fun ModelFormat.engineKind(): EngineKind = when (this) {
    ModelFormat.GGUF -> EngineKind.LLAMA_CPP
    ModelFormat.LITERTLM -> EngineKind.LITERT_LM
}
