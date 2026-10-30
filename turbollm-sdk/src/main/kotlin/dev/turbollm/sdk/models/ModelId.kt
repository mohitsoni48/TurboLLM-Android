package dev.turbollm.sdk.models

import java.io.File

/**
 * A model's id: its path relative to the model root, with forward slashes, without the extension and without
 * a split suffix, e.g. `unsloth/Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M`. Stable and readable with no metadata parse.
 */
internal fun modelId(root: File, file: File): String {
    val folder = file.relativeTo(root).invariantSeparatorsPath.substringBeforeLast('/', missingDelimiterValue = "")
    val name = modelName(file.name)
    return if (folder.isEmpty()) name else "$folder/$name"
}

private fun modelName(fileName: String): String = splitShard(fileName)?.stem ?: fileName.substringBeforeLast('.')
