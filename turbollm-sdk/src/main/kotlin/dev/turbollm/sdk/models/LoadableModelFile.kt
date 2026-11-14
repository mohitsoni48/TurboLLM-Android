package dev.turbollm.sdk.models

import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.TurboLLMException
import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException

private const val PATH_PARAM = "path"

/** A model file a caller named by path, canonicalised and checked; nothing about it is written or executed. */
internal class LoadableModelFile(val file: File, val format: ModelFormat)

/**
 * Checks a load-by-path request (architecture §4.3): the path is absolute; its canonical form (symlinks and `..`
 * resolved) is a readable regular file with a `.gguf` or `.litertlm` name and that format's magic; and a split
 * GGUF is named by shard 1 of a complete group. Every refusal is `invalid_request` on `path`, naming the rule.
 */
internal fun loadableModelFile(path: String): LoadableModelFile {
    val requested = File(path)
    if (!requested.isAbsolute) refusePath("The path '$path' is not absolute.")
    val file = realFileOf(requested)
    if (!file.isFile || !file.canRead()) refusePath("'$file' is not a readable regular file.")
    val format = modelFormatOf(file.name)
        ?: refusePath("'${file.name}' has neither a .gguf nor a .litertlm extension.")
    if (isVisionProjector(file.name)) refusePath("'${file.name}' is a vision projector, not a model.")
    if (!format.hasMagic(file)) refusePath("'${file.name}' does not start with the ${format.magicName()} magic.")
    val shard = splitShard(file.name)
    val sizeBytes = if (shard == null) file.length() else completeGroupSize(file, shard)
    if (!format.hasPlausibleSize(sizeBytes)) refusePath("'${file.name}' is under 1 MiB, so it is not a whole GGUF.")
    return LoadableModelFile(file, format)
}

/**
 * [requested] with every symlink and `..` resolved, so the checks judge the file that would actually be read.
 * (`File.canonicalFile` does not resolve symlinks on every platform.)
 */
private fun realFileOf(requested: File): File = try {
    requested.toPath().toRealPath().toFile()
} catch (unreadable: IOException) {
    refusePath("'$requested' is not a readable regular file (${unreadable.javaClass.simpleName}).")
} catch (malformed: InvalidPathException) {
    refusePath("'$requested' is not a valid path: ${malformed.reason}.")
}

/** The total size of the split group [firstShard] opens, once every one of its shards is present. */
private fun completeGroupSize(firstShard: File, shard: SplitShard): Long {
    if (shard.index != 1) {
        refusePath("'${firstShard.name}' is shard ${shard.index} of ${shard.total}; load shard 1 of the group.")
    }
    val shardsByIndex = groupShards(firstShard.parentFile, shard)
    val missing = (1..shard.total).firstOrNull { it !in shardsByIndex }
    if (missing != null) refusePath("'${firstShard.name}' is missing shard $missing of ${shard.total}.")
    return shardsByIndex.values.sumOf { it.length() }
}

/** The group's shards in [folder] that are files with the GGUF magic; any other shard counts as missing. */
private fun groupShards(folder: File?, shard: SplitShard): Map<Int, File> =
    folder?.listFiles().orEmpty()
        .mapNotNull { sibling -> splitShard(sibling.name)?.let { it to sibling } }
        .filter { (sibling, file) -> sibling.isInGroupOf(shard) && file.isFile && ModelFormat.GGUF.hasMagic(file) }
        .associate { (sibling, file) -> sibling.index to file }

private fun SplitShard.isInGroupOf(other: SplitShard) = stem == other.stem && total == other.total

private fun ModelFormat.magicName(): String = when (this) {
    ModelFormat.GGUF -> "GGUF"
    ModelFormat.LITERTLM -> "LITERTLM"
}

private fun refusePath(message: String): Nothing = throw TurboLLMException.InvalidRequest(PATH_PARAM, message)
