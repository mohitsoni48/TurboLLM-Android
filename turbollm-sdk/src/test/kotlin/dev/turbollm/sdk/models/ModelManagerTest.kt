package dev.turbollm.sdk.models

import dev.turbollm.sdk.DownloadState
import dev.turbollm.sdk.HfModelFile
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelManager
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.testing.FakeHttpFetcher
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fetcher = FakeHttpFetcher()
    private var loadedModelId: String? = null

    @Test
    fun aDownloadExpandsThroughHuggingFaceAndShowsUpAsInstalled() = runTest {
        serveRepo()
        val models = modelManager()
        assertTrue(models.installed().isEmpty())

        val handle = models.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(DownloadState.DONE, models.downloads.value.single { it.id == handle.id }.state)
        assertEquals(listOf("$REPO/Qwen3-1.7B-Q4_K_M"), models.installed().map { it.id })
    }

    @Test
    fun filesListsTheRepoThroughTheCatalog() = runTest {
        serveRepo()

        val files = modelManager().files(REPO)

        val expected = HfModelFile(FILE, ModelFormat.GGUF, CONTENT.size.toLong(), "Q4_K_M", 1, sha256Of(CONTENT))
        assertEquals(listOf(expected), files)
    }

    @Test
    fun deleteRefusesTheLoadedModel() = runTest {
        val model = installModel()
        loadedModelId = ID
        val models = modelManager()

        try {
            models.delete(ID)
            fail("deleting the loaded model must be refused")
        } catch (refused: IllegalStateException) {
            assertTrue(model.exists())
        }
    }

    @Test
    fun deleteRemovesAModelThatIsNotLoaded() = runTest {
        val model = installModel()
        val models = modelManager()

        models.delete(ID)

        assertFalse(model.exists())
        assertTrue(models.installed().isEmpty())
    }

    @Test
    fun pauseResumeAndCancelReachTheDownloader() = runTest {
        serveRepo()
        val models = modelManager()
        val handle = models.download(REPO, FILE)

        models.pause(handle.id)
        advanceUntilIdle()
        assertEquals(DownloadState.PAUSED, models.downloads.value.single().state)
        models.resume(handle.id)
        advanceUntilIdle()
        assertEquals(DownloadState.DONE, models.downloads.value.single().state)
        models.cancel(handle.id)
        advanceUntilIdle()

        assertTrue(models.downloads.value.isEmpty())
    }

    private fun TestScope.modelManager(): ModelManager {
        val store = ModelStore(folder.root, Clock { 0 }) { loadedModelId }
        val catalog = HfCatalog(fetcher, BASE, token = null, Clock { testScheduler.currentTime })
        val downloadDispatcher = StandardTestDispatcher(testScheduler)
        val environment = DownloadEnvironment(
            scope = CoroutineScope(SupervisorJob() + downloadDispatcher),
            io = downloadDispatcher,
            clock = Clock { testScheduler.currentTime },
            freeBytes = { Long.MAX_VALUE },
        )
        val downloader = Downloader(
            source = HfFileSource(fetcher, BASE, token = null),
            planner = DownloadPlanner(catalog::expand),
            root = folder.root,
            environment = environment,
            onModelInstalled = store::invalidate,
        )
        return ModelManagerImpl(store, catalog, downloader)
    }

    private fun serveRepo() {
        fetcher.respond(
            "$BASE/api/models/$REPO/revision/main?expand%5B%5D=sha",
            body = """{"sha":"$COMMIT"}""".toByteArray(),
        )
        val tree = """[{"type":"file","path":"$FILE","size":134,""" +
            """"lfs":{"oid":"${sha256Of(CONTENT)}","size":${CONTENT.size}}}]"""
        fetcher.respond("$BASE/api/models/$REPO/tree/$COMMIT?recursive=true", body = tree.toByteArray())
        fetcher.respond("$BASE/$REPO/resolve/$COMMIT/$FILE", body = CONTENT)
    }

    private fun installModel(): File = File(folder.root, "$REPO/$FILE").apply {
        parentFile!!.mkdirs()
        writeBytes(CONTENT)
    }

    private companion object {
        const val BASE = "https://hf.test"
        const val REPO = "unsloth/Qwen3-1.7B-GGUF"
        const val FILE = "Qwen3-1.7B-Q4_K_M.gguf"
        const val ID = "$REPO/Qwen3-1.7B-Q4_K_M"
        val COMMIT = "c".repeat(40)

        /** The GGUF magic, padded past the store's 1 MiB floor. */
        val CONTENT = "GGUF".toByteArray() + ByteArray(1024 * 1024)

        fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
