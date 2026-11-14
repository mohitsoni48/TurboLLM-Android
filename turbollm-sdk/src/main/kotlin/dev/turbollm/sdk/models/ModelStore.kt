package dev.turbollm.sdk.models

import dev.turbollm.sdk.InstalledModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.internal.Clock
import java.io.File
import java.nio.file.Files

private const val MAX_WALK_DEPTH = 8
private const val RESCAN_AFTER_MILLIS = 2_000L
private val PARTIAL_SUFFIXES = listOf(".part", ".import-part")

/**
 * The installed, complete-or-not models under the app-chosen model root, with stable readable ids. Other
 * writers (the TurboLLM daemon's downloads and imports) share the folder without calling [invalidate], so a
 * listing older than two seconds is rescanned.
 */
internal class ModelStore(
    private val root: File,
    private val clock: Clock,
    private val loadedModelId: () -> String?,
) {
    private var scanned: List<ScannedModel> = emptyList()
    private var scannedAt: Long? = null

    @Synchronized
    fun list(): List<InstalledModel> = currentScan().map { it.model }

    @Synchronized
    fun invalidate() {
        scannedAt = null
    }

    /** Deletes every file of the model. Refuses the loaded model (IllegalStateException). */
    @Synchronized
    fun delete(id: String) {
        check(loadedModelId() != id) { "The model '$id' is loaded; unload it before deleting it." }
        invalidate()
        val target = currentScan().firstOrNull { it.model.id == id }
            ?: throw TurboLLMException.ModelNotFound("No installed model has the id '$id'.")
        target.files.forEach { Files.deleteIfExists(it.toPath()) }
        invalidate()
    }

    private fun currentScan(): List<ScannedModel> {
        val now = clock.nowMillis()
        val lastScan = scannedAt
        if (lastScan == null || now - lastScan >= RESCAN_AFTER_MILLIS) {
            scanned = scan()
            scannedAt = now
        }
        return scanned
    }

    private fun scan(): List<ScannedModel> {
        val (shards, singles) = modelFiles().partition { splitShard(it.name) != null }
        val models = singles.mapNotNull(::singleFileModel) + shards.groupBy(::splitGroupKey).values.mapNotNull(::splitModel)
        return models.sortedBy { it.model.id }
    }

    private fun modelFiles(): List<File> = root.walkTopDown()
        .maxDepth(MAX_WALK_DEPTH)
        .onEnter { dir -> dir == root || !dir.name.startsWith(".") }
        .filter { it.isFile && isInstalledModelFile(it) }
        .toList()

    private fun isInstalledModelFile(file: File): Boolean {
        if (PARTIAL_SUFFIXES.any { file.name.endsWith(it) } || isVisionProjector(file.name)) return false
        val format = modelFormatOf(file.name) ?: return false
        return format.hasMagic(file)
    }

    private fun singleFileModel(file: File): ScannedModel? {
        val format = modelFormatOf(file.name) ?: return null
        if (!format.hasPlausibleSize(file.length())) return null
        val model = InstalledModel(
            id = modelId(root, file),
            path = file.absolutePath,
            format = format,
            sizeBytes = file.length(),
            quant = quantFromName(file.name, format),
            shards = 1,
            complete = true,
        )
        return ScannedModel(model, listOf(file))
    }

    private fun splitModel(shardFiles: List<File>): ScannedModel? {
        val shards = shardFiles.associateBy { checkNotNull(splitShard(it.name)) }
        val total = shards.keys.maxOf { it.total }
        val sizeBytes = shardFiles.sumOf { it.length() }
        if (!ModelFormat.GGUF.hasPlausibleSize(sizeBytes)) return null
        val firstShard = shards.entries.firstOrNull { it.key.index == 1 }?.value
            ?: File(shardFiles.first().parentFile, shards.keys.first().firstShardName())
        val model = InstalledModel(
            id = modelId(root, firstShard),
            path = firstShard.absolutePath,
            format = ModelFormat.GGUF,
            sizeBytes = sizeBytes,
            quant = quantFromName(firstShard.name, ModelFormat.GGUF),
            shards = total,
            complete = shards.keys.map { it.index }.toSet() == (1..total).toSet(),
        )
        return ScannedModel(model, shardFiles)
    }

    private fun splitGroupKey(shardFile: File): Pair<File?, String> =
        shardFile.parentFile to checkNotNull(splitShard(shardFile.name)).stem

    private class ScannedModel(val model: InstalledModel, val files: List<File>)
}
