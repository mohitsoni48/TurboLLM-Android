package dev.turbollm.sdk.models

import dev.turbollm.sdk.HfModelFile
import java.net.MalformedURLException
import java.net.URL
import kotlinx.serialization.Serializable

private const val FILE_ENTRY = "file"
private val NEXT_PAGE_LINK = Regex("""<([^>]+)>\s*;[^,]*\brel="?next"?""", RegexOption.IGNORE_CASE)

/** One entry of Hugging Face's recursive tree listing. LFS files carry their real size and sha256 in [lfs]. */
@Serializable
internal data class RawTreeEntry(
    val type: String? = null,
    val path: String,
    val size: Long? = null,
    val lfs: RawLfs? = null,
)

@Serializable
internal data class RawLfs(val oid: String? = null, val size: Long? = null)

/** A file to download: its repo path, its size and, when Hugging Face stores it in LFS, its sha256. */
internal data class HfRemoteFile(val path: String, val sizeBytes: Long, val sha256: String?)

/**
 * The model files of a repo's tree: GGUF and `.litertlm`, projectors left out (no vision in M1). Split GGUF
 * shards are grouped by folder, stem and shard count, as the daemon's `groupFiles` does.
 */
internal class HfTree(entries: List<RawTreeEntry>) {

    private val groups: List<List<RawTreeEntry>> = entries
        .filter(::isModelFile)
        .groupBy(::groupKey)
        .values
        .map { group -> group.sortedBy { it.path } }

    fun modelFiles(): List<HfModelFile> = groups.map(::modelFile).sortedBy { it.path }

    /** Every file of the model whose first shard (or single file) is at [path]; null when there is none. */
    fun filesOf(path: String): List<HfRemoteFile>? =
        groups.firstOrNull { it.first().path == path }?.map { HfRemoteFile(it.path, it.sizeBytes(), it.lfs?.oid) }

    private fun modelFile(group: List<RawTreeEntry>): HfModelFile {
        val first = group.first()
        val name = baseName(first.path)
        val format = checkNotNull(modelFormatOf(name))
        val shards = splitShard(name)?.total ?: 1
        return HfModelFile(
            path = first.path,
            format = format,
            sizeBytes = group.sumOf { it.sizeBytes() },
            quant = quantFromName(name, format),
            shards = shards,
            sha256 = first.lfs?.oid.takeIf { shards == 1 },
        )
    }
}

/** The `rel="next"` target of a `Link` header, resolved against [current], only when it stays on that origin. */
internal fun nextPageUrl(link: String?, current: String): String? {
    val target = link?.let { NEXT_PAGE_LINK.find(it) }?.groupValues?.get(1) ?: return null
    val currentUrl = URL(current)
    val next = try {
        URL(currentUrl, target)
    } catch (invalid: MalformedURLException) {
        return null
    }
    return next.toString().takeIf { next.hasSameOriginAs(currentUrl) }
}

private fun isModelFile(entry: RawTreeEntry): Boolean {
    val name = baseName(entry.path)
    return entry.type == FILE_ENTRY && modelFormatOf(name) != null && !isVisionProjector(name)
}

private fun groupKey(entry: RawTreeEntry): String {
    val shard = splitShard(baseName(entry.path)) ?: return entry.path
    return "${folderOf(entry.path)}|${shard.stem}|${shard.total}"
}

private fun RawTreeEntry.sizeBytes(): Long = lfs?.size ?: size ?: 0

private fun baseName(path: String): String = path.substringAfterLast('/')

private fun folderOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")
