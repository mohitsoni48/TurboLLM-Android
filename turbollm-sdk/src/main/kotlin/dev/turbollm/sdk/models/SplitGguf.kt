package dev.turbollm.sdk.models

import java.util.Locale

/** The daemon's split rule (`turbollm/src/hf/hf.ts` `SPLIT_RE`). */
private val SPLIT_SHARD_NAME = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

internal data class SplitShard(val stem: String, val index: Int, val total: Int) {
    fun firstShardName(): String = String.format(Locale.ROOT, "%s-%05d-of-%05d.gguf", stem, 1, total)
}

/** The shard a file name denotes, or null for a file that is not part of a split GGUF. */
internal fun splitShard(fileName: String): SplitShard? =
    SPLIT_SHARD_NAME.matchEntire(fileName)?.destructured?.let { (stem, index, total) ->
        SplitShard(stem = stem, index = index.toInt(), total = total.toInt())
    }
