package dev.turbollm.sdk

import kotlinx.coroutines.flow.StateFlow

/** The models under the model root, Hugging Face search, and resumable verified downloads into the root. */
interface ModelManager {
    suspend fun installed(): List<InstalledModel>
    suspend fun search(query: String, sort: HfSort = HfSort.TRENDING, limit: Int = 30): List<HfRepo>

    /** Grouped: a split GGUF is one entry. */
    suspend fun files(repo: String): List<HfModelFile>

    /** Idempotent per (repo, file): a second call returns the row the first one made. */
    fun download(repo: String, file: String): DownloadHandle
    val downloads: StateFlow<List<DownloadStatus>>
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)

    /** Refuses the loaded model. */
    suspend fun delete(modelId: String)
}

/**
 * A model under the model root. A split GGUF is one entry: [path] is its first shard, [shards] the group size,
 * and [complete] is false while a shard is missing (listed, but not loadable).
 */
data class InstalledModel(
    val id: String,
    val path: String,
    val format: ModelFormat,
    val sizeBytes: Long,
    val quant: String?,
    val shards: Int,
    val complete: Boolean,
)

/** The order of a Hugging Face search. */
enum class HfSort { TRENDING, DOWNLOADS, LIKES, MODIFIED }

/** A Hugging Face repo that ships a format the SDK runs; [formats] says which searches found it. */
data class HfRepo(
    val repo: String,
    val downloads: Long,
    val likes: Long,
    val updatedAt: String?,
    val gated: Boolean,
    val formats: Set<ModelFormat>,
)

/** One downloadable model in a repo. A split GGUF is one entry: [path] is its first shard, [shards] the count. */
data class HfModelFile(
    val path: String,
    val format: ModelFormat,
    val sizeBytes: Long,
    val quant: String?,
    val shards: Int,
    val sha256: String?,
)

/** One model download: every shard of [file] in [repo]. [error] says why a row is [DownloadState.ERROR]. */
data class DownloadStatus(
    val id: String,
    val repo: String,
    val file: String,
    val state: DownloadState,
    val bytes: Long,
    val total: Long,
    val bytesPerSec: Long,
    val error: String?,
)

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, ERROR }

/** The row [ModelManager.download] made or found; follow it in [ModelManager.downloads] by [id]. */
data class DownloadHandle(val id: String, val repo: String, val file: String)

/** Why a download failed. Kotlin API only: it is never sent over HTTP in M1. */
class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause)
