package dev.turbollm.sdk.chat

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.InstalledModel
import dev.turbollm.sdk.LoadedModel
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.engine.LoadTarget
import dev.turbollm.sdk.models.ModelStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the model a chat request names (architecture §4.2): the loaded model by its id or absolute path; else
 * an installed, complete model, loaded first when [autoLoadOnRequest] is on and `ModelNotLoaded` otherwise;
 * else `ModelNotFound`.
 */
internal class ModelResolver(
    private val store: ModelStore,
    private val host: EngineHost,
    private val autoLoadOnRequest: Boolean,
    private val cacheDir: File,
) {
    suspend fun resolve(model: String): LoadedModel = loadedModelNamed(model) ?: loadOnRequest(installedModel(model))

    private fun loadedModelNamed(model: String): LoadedModel? =
        (host.state.value as? EngineState.Ready)?.model?.takeIf { model == it.id || model == it.path }

    private suspend fun installedModel(id: String): InstalledModel {
        val installed = withContext(Dispatchers.IO) { store.list() }.firstOrNull { it.id == id && it.complete }
        return installed ?: throw TurboLLMException.ModelNotFound("No installed, complete model has the id '$id'.")
    }

    private suspend fun loadOnRequest(model: InstalledModel): LoadedModel {
        if (!autoLoadOnRequest) throw TurboLLMException.ModelNotLoaded("The model '${model.id}' is not loaded.")
        return host.load(model.loadTarget())
    }

    private fun InstalledModel.loadTarget() = LoadTarget(
        ref = ModelRef.Installed(id),
        id = id,
        format = format,
        spec = LoadSpec(File(path), contextSize = null, threads = null, backend = Backend.AUTO, cacheDir = cacheDir),
    )
}
