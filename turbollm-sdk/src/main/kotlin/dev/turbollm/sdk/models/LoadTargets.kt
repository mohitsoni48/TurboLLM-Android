package dev.turbollm.sdk.models

import dev.turbollm.sdk.LoadOptions
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.engine.LoadTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a [ModelRef] and its [LoadOptions] into the [LoadTarget] the engine host loads: an installed, complete
 * model by its id, or (only with [allowLoadByPath]) a checked file by path. A file under [root] keeps its
 * installed id; one outside it is known by its absolute path, as `/v1/models` lists it.
 */
internal class LoadTargets(
    private val store: ModelStore,
    private val root: File,
    private val allowLoadByPath: Boolean,
    private val cacheDir: File,
) {
    suspend fun of(ref: ModelRef, options: LoadOptions): LoadTarget {
        requirePositive(options.contextSize, "contextSize")
        requirePositive(options.threads, "threads")
        return withContext(Dispatchers.IO) {
            when (ref) {
                is ModelRef.Installed -> installedTarget(ref, options)
                is ModelRef.File -> fileTarget(ref, options)
            }
        }
    }

    private fun installedTarget(ref: ModelRef.Installed, options: LoadOptions): LoadTarget {
        val model = store.list().firstOrNull { it.id == ref.id && it.complete }
            ?: throw TurboLLMException.ModelNotFound("No installed, complete model has the id '${ref.id}'.")
        return LoadTarget(ref, model.id, model.format, options.toSpec(File(model.path)))
    }

    private fun fileTarget(ref: ModelRef.File, options: LoadOptions): LoadTarget {
        if (!allowLoadByPath) throw TurboLLMException.LoadByPathDisabled()
        val loadable = loadableModelFile(ref.path)
        return LoadTarget(ref, idOf(loadable.file), loadable.format, options.toSpec(loadable.file))
    }

    /** [file] is a real path (symlinks resolved), so it is compared with the root's real path. */
    private fun idOf(file: File): String {
        val realRoot = root.takeIf { it.isDirectory }?.toPath()?.toRealPath()?.toFile()
        return if (realRoot != null && file.startsWith(realRoot)) modelId(realRoot, file) else file.path
    }

    private fun LoadOptions.toSpec(file: File) = LoadSpec(file, contextSize, threads, backend, cacheDir)
}

private fun requirePositive(value: Int?, param: String) {
    if (value != null && value < 1) {
        throw TurboLLMException.InvalidRequest(param, "'$param' must be at least 1, but was $value.")
    }
}
