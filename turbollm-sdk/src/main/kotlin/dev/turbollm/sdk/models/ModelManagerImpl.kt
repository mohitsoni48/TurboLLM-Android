package dev.turbollm.sdk.models

import dev.turbollm.sdk.DownloadHandle
import dev.turbollm.sdk.DownloadStatus
import dev.turbollm.sdk.HfModelFile
import dev.turbollm.sdk.HfRepo
import dev.turbollm.sdk.HfSort
import dev.turbollm.sdk.InstalledModel
import dev.turbollm.sdk.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The public [ModelManager]: the store for what is installed, the catalog for Hugging Face, the downloader. */
internal class ModelManagerImpl(
    private val store: ModelStore,
    private val catalog: HfCatalog,
    private val downloader: Downloader,
) : ModelManager {

    override val downloads: StateFlow<List<DownloadStatus>> get() = downloader.downloads

    override suspend fun installed(): List<InstalledModel> = withContext(Dispatchers.IO) { store.list() }

    override suspend fun search(query: String, sort: HfSort, limit: Int): List<HfRepo> =
        catalog.search(query, sort, limit)

    override suspend fun files(repo: String): List<HfModelFile> = catalog.files(repo)

    override fun download(repo: String, file: String): DownloadHandle = downloader.download(repo, file)

    override suspend fun pause(id: String) = downloader.pause(id)

    override suspend fun resume(id: String) = downloader.resume(id)

    override suspend fun cancel(id: String) = downloader.cancel(id)

    override suspend fun delete(modelId: String) = withContext(Dispatchers.IO) { store.delete(modelId) }
}
