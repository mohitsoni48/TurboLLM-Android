package dev.turbollm.sdk.models

import dev.turbollm.sdk.DownloadState
import dev.turbollm.sdk.DownloadStatus
import dev.turbollm.sdk.HfError
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.testing.FakeHttpFetcher
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fetcher = FakeHttpFetcher()
    private val plans = mutableMapOf<String, HfDownloadPlan>()
    private var freeBytes = Long.MAX_VALUE
    private var installs = 0

    private val root: File get() = folder.root
    private val dest: File get() = File(root, "$REPO/$FILE")
    private val part: File get() = File(root, "$REPO/$FILE.part")

    @Test
    fun aDownloadIsFetchedAtTheCommitThroughAPartFileIntoTheRepoFolder() = runTest {
        planSingleFile()
        serve(URL, CONTENT)
        val downloader = downloader()

        val handle = downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertArrayEquals(CONTENT, dest.readBytes())
        assertFalse(part.exists())
        val expected = DownloadStatus(handle.id, REPO, FILE, DownloadState.DONE, 1000, 1000, 0, null)
        assertEquals(listOf(expected), downloader.downloads.value)
        assertEquals(1, installs)
        assertEquals(listOf(URL), fetcher.requests.map { it.url })
    }

    @Test
    fun everyFileRequestAsksForTheRawBytesAndCarriesTheToken() = runTest {
        planSingleFile()
        serve(URL, CONTENT)

        downloader().download(REPO, FILE)
        advanceUntilIdle()

        val headers = fetcher.requests.single().headers
        assertEquals("identity", headers["Accept-Encoding"])
        assertEquals("Bearer $TOKEN", headers["Authorization"])
        assertNull(headers["Range"])
    }

    @Test
    fun aPartFileOnDiskIsResumedWithARangeRequest() = runTest {
        planSingleFile()
        writePart(CONTENT.copyOfRange(0, 400))
        serve(URL, CONTENT)

        downloader().download(REPO, FILE)
        advanceUntilIdle()

        assertEquals("bytes=400-", fetcher.requests.single().headers["Range"])
        assertNull(fetcher.requests.single().headers["If-Range"])
        assertArrayEquals(CONTENT, dest.readBytes())
    }

    @Test
    fun aResumeSendsTheStrongValidatorAsIfRange() = runTest {
        val id = pausePartway(etag = "\"v1\"")
        serve(URL, CONTENT, etag = "\"v1\"")
        val restarted = downloader()

        restarted.resume(id)
        advanceUntilIdle()

        val resumed = fetcher.requests.last().headers
        assertEquals("bytes=300-", resumed["Range"])
        assertEquals("\"v1\"", resumed["If-Range"])
        assertArrayEquals(CONTENT, dest.readBytes())
    }

    @Test
    fun aWeakEtagIsNeverSentAsIfRange() = runTest {
        val id = pausePartway(etag = "W/\"v1\"")
        serve(URL, CONTENT, etag = "W/\"v1\"")
        val restarted = downloader()

        restarted.resume(id)
        advanceUntilIdle()

        assertEquals("bytes=300-", fetcher.requests.last().headers["Range"])
        assertNull(fetcher.requests.last().headers["If-Range"])
        assertEquals(DownloadState.DONE, restarted.downloads.value.single().state)
    }

    @Test
    fun aTwoHundredToARangeRequestRestartsFromByteZero() = runTest {
        planSingleFile()
        writePart(ByteArray(400) { 7 })
        fetcher.respondTo(URL) { HttpResponse(200, URL, emptyMap(), CONTENT.inputStream()) }

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals("bytes=400-", fetcher.requests.single().headers["Range"])
        assertArrayEquals(CONTENT, dest.readBytes())
        assertEquals(DownloadState.DONE, downloader.downloads.value.single().state)
    }

    @Test
    fun aFourSixteenFinishesWhenThePartIsTheWholeFileAndItsHashMatches() = runTest {
        planSingleFile()
        writePart(CONTENT)
        fetcher.respondTo(URL) { HttpResponse(416, URL, emptyMap(), ByteArray(0).inputStream()) }

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(1, fetcher.requests.size)
        assertArrayEquals(CONTENT, dest.readBytes())
        assertEquals(DownloadState.DONE, downloader.downloads.value.single().state)
        assertEquals(1, installs)
    }

    @Test
    fun aFourSixteenWithAMismatchedHashRestartsOnceWithoutRange() = runTest {
        planSingleFile()
        writePart(ByteArray(1000) { 7 })
        fetcher.respondTo(URL) { request ->
            if (request.headers.containsKey("Range")) HttpResponse(416, URL, emptyMap(), ByteArray(0).inputStream())
            else HttpResponse(200, URL, emptyMap(), CONTENT.inputStream())
        }

        downloader().download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(2, fetcher.requests.size)
        assertNull(fetcher.requests[1].headers["Range"])
        assertNull(fetcher.requests[1].headers["If-Range"])
        assertArrayEquals(CONTENT, dest.readBytes())
    }

    @Test
    fun aSecondFourSixteenAfterTheRestartEndsInError() = runTest {
        planSingleFile()
        writePart(ByteArray(1000) { 7 })
        fetcher.respondTo(URL) { HttpResponse(416, URL, emptyMap(), ByteArray(0).inputStream()) }

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(2, fetcher.requests.size)
        assertEquals(DownloadState.ERROR, downloader.downloads.value.single().state)
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun aContentRangeThatDoesNotContinueThePartDeletesItAndFails() = runTest {
        planSingleFile()
        writePart(CONTENT.copyOfRange(0, 400))
        fetcher.respondTo(URL) {
            HttpResponse(206, URL, mapOf("Content-Range" to "bytes 0-999/1000"), CONTENT.inputStream())
        }

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.ERROR, status.state)
        assertTrue(status.error!!.contains("does not continue the partial file"))
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun aResumeWhoseValidatorChangedDeletesThePartAndFails() = runTest {
        val id = pausePartway(etag = "\"v1\"")
        fetcher.respondTo(URL) {
            val headers = mapOf("ETag" to "\"v2\"", "Content-Range" to "bytes 300-999/1000")
            HttpResponse(206, URL, headers, CONTENT.copyOfRange(300, 1000).inputStream())
        }
        val restarted = downloader()

        restarted.resume(id)
        advanceUntilIdle()

        assertEquals(DownloadState.ERROR, restarted.downloads.value.single().state)
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun aShortBodyFailsAsADownloadErrorAndDeletesThePart() = runTest {
        planSingleFile()
        serve(URL, CONTENT.copyOfRange(0, 600))

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.ERROR, status.state)
        assertTrue(status.error!!.contains("size did not match"))
        assertEquals(0, status.bytes)
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun aShaMismatchDeletesThePartAndFails() = runTest {
        planSingleFile(sha256 = sha256Of(ByteArray(1000)))
        serve(URL, CONTENT)

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.ERROR, status.state)
        assertTrue(status.error!!.contains("Checksum failed"))
        assertEquals(0, status.bytes)
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun aDroppedConnectionKeepsThePartAndIsNeverRetriedOnItsOwn() = runTest {
        planSingleFile()
        serve(URL, CONTENT) { ScriptedBody(it, failAtRead = 4) }
        val downloader = downloader()

        val id = downloader.download(REPO, FILE).id
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.ERROR, status.state)
        assertEquals(300, status.bytes)
        assertEquals(300, part.length())
        assertEquals(1, fetcher.requests.size)

        serve(URL, CONTENT)
        downloader.resume(id)
        advanceUntilIdle()

        assertEquals("bytes=300-", fetcher.requests.last().headers["Range"])
        assertArrayEquals(CONTENT, dest.readBytes())
    }

    @Test
    fun aModelThatNeedsMoreThanTheFreeSpaceIsRefusedBeforeAnyByteIsWritten() = runTest {
        plans[FILE] = HfDownloadPlan(COMMIT, listOf(HfRemoteFile(FILE, 2_000_000_000, null)))
        freeBytes = 1_000_000_000

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.ERROR, status.state)
        val error = status.error!!
        assertTrue(error, error.contains("2.2 GB") && error.contains("1.0 GB"))
        assertTrue(fetcher.requests.isEmpty())
        assertFalse(part.exists())
    }

    @Test
    fun freeSpaceOfExactlyOnePointOneTimesTheSizeIsEnough() = runTest {
        planSingleFile()
        serve(URL, CONTENT)
        freeBytes = 1100

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(DownloadState.DONE, downloader.downloads.value.single().state)
    }

    @Test
    fun freeSpaceJustUnderOnePointOneTimesTheSizeIsRefused() = runTest {
        planSingleFile()
        serve(URL, CONTENT)
        freeBytes = 1099

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(DownloadState.ERROR, downloader.downloads.value.single().state)
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun rowsInTheManifestComeBackPausedWithThePartSize() = runTest {
        val id = pausePartway(etag = "\"v1\"")

        val restarted = downloader()

        assertTrue(File(root, ".turbollm-sdk/downloads.json").readText().contains(id))
        val expected = DownloadStatus(id, REPO, FILE, DownloadState.PAUSED, 300, 1000, 0, null)
        assertEquals(listOf(expected), restarted.downloads.value)
    }

    @Test
    fun aRowLeftRunningByAKilledProcessComesBackPaused() = runTest {
        writePart(CONTENT.copyOfRange(0, 250))
        File(root, ".turbollm-sdk").mkdirs()
        File(root, ".turbollm-sdk/downloads.json").writeText(
            """{"rows":[{"id":"dl-1","repo":"$REPO","file":"$FILE","sha":"$COMMIT","path":"$REPO/$FILE",""" +
                """"total":1000,"state":"RUNNING","shards":[{"path":"$FILE","size":1000}]}]}""",
        )

        val restarted = downloader()

        val expected = DownloadStatus("dl-1", REPO, FILE, DownloadState.PAUSED, 250, 1000, 0, null)
        assertEquals(listOf(expected), restarted.downloads.value)
    }

    @Test
    fun downloadingTheSameFileTwiceReusesItsRow() = runTest {
        planSingleFile()
        serve(URL, CONTENT)
        val downloader = downloader()

        val first = downloader.download(REPO, FILE)
        val second = downloader.download(REPO, FILE)
        advanceUntilIdle()
        val afterDone = downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(first, second)
        assertEquals(first, afterDone)
        assertEquals(1, downloader.downloads.value.size)
        assertEquals(1, fetcher.requests.size)
    }

    @Test
    fun atMostTwoDownloadsRunAtOnceAndAThirdStaysQueued() = runTest {
        val files = listOf("a.gguf", "b.gguf", "c.gguf")
        files.forEach { planAndServe(it) }
        val downloader = downloader()

        files.forEach { downloader.download(REPO, it) }

        val states = downloader.downloads.value.map { it.state }
        assertEquals(listOf(DownloadState.RUNNING, DownloadState.RUNNING, DownloadState.QUEUED), states)
        advanceUntilIdle()
        assertTrue(downloader.downloads.value.all { it.state == DownloadState.DONE })
    }

    @Test
    fun aPausedQueuedRowNeverStartsUntilResumed() = runTest {
        val files = listOf("a.gguf", "b.gguf", "c.gguf")
        files.forEach { planAndServe(it) }
        val downloader = downloader()
        val third = files.map { downloader.download(REPO, it) }.last()

        downloader.pause(third.id)
        advanceUntilIdle()

        assertEquals(DownloadState.PAUSED, downloader.downloads.value.single { it.id == third.id }.state)
        assertTrue(fetcher.requests.none { it.url.endsWith("/c.gguf") })

        downloader.resume(third.id)
        advanceUntilIdle()
        assertEquals(DownloadState.DONE, downloader.downloads.value.single { it.id == third.id }.state)
    }

    @Test
    fun pausingMidTransferKeepsThePartAndResumeContinuesIt() = runTest {
        planSingleFile()
        lateinit var downloader: Downloader
        lateinit var id: String
        serve(URL, CONTENT) { ScriptedBody(it, onRead = { read -> if (read == 4) downloader.pause(id) }) }
        downloader = downloader()
        id = downloader.download(REPO, FILE).id
        advanceUntilIdle()

        assertEquals(DownloadState.PAUSED, downloader.downloads.value.single().state)
        assertEquals(300, part.length())

        serve(URL, CONTENT)
        downloader.resume(id)
        advanceUntilIdle()

        assertEquals("bytes=300-", fetcher.requests.last().headers["Range"])
        assertArrayEquals(CONTENT, dest.readBytes())
    }

    @Test
    fun cancellingMidTransferRemovesTheRowAndThePart() = runTest {
        planSingleFile()
        lateinit var downloader: Downloader
        lateinit var id: String
        serve(URL, CONTENT) { ScriptedBody(it, onRead = { read -> if (read == 4) downloader.cancel(id) }) }
        downloader = downloader()
        id = downloader.download(REPO, FILE).id
        advanceUntilIdle()

        assertTrue(downloader.downloads.value.isEmpty())
        assertFalse(part.exists())
        assertFalse(dest.exists())
    }

    @Test
    fun cancellingAPausedRowRemovesItsPart() = runTest {
        val id = pausePartway(etag = null)
        val restarted = downloader()

        restarted.cancel(id)
        advanceUntilIdle()

        assertTrue(restarted.downloads.value.isEmpty())
        assertFalse(part.exists())
    }

    @Test
    fun aSplitModelDownloadsEveryShardAsOneRow() = runTest {
        val shards = listOf("Big-Q4_K_M-00001-of-00002.gguf", "Big-Q4_K_M-00002-of-00002.gguf")
        plans[shards[0]] = HfDownloadPlan(COMMIT, shards.map { HfRemoteFile(it, 1000, sha256Of(CONTENT)) })
        shards.forEach { serve(urlOf(it), CONTENT) }

        val downloader = downloader()
        downloader.download(REPO, shards[0])
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.DONE, status.state)
        assertEquals(2000, status.total)
        shards.forEach { assertArrayEquals(CONTENT, File(root, "$REPO/$it").readBytes()) }
        assertEquals(1, installs)
    }

    @Test
    fun aPathThatLeavesTheModelFolderIsRefused() = runTest {
        plans[FILE] = HfDownloadPlan(COMMIT, listOf(HfRemoteFile("../../escape.gguf", 1000, null)))

        val downloader = downloader()
        downloader.download(REPO, FILE)
        advanceUntilIdle()

        assertEquals(DownloadState.ERROR, downloader.downloads.value.single().state)
        assertTrue(fetcher.requests.isEmpty())
        assertFalse(File(root.parentFile, "escape.gguf").exists())
    }

    @Test
    fun aRejectedTokenAndAGatedRepoEndInError() = runTest {
        val replies = mapOf(401 to "token", 403 to "gated")
        replies.forEach { (status, phrase) ->
            val file = "q$status.gguf"
            plans[file] = HfDownloadPlan(COMMIT, listOf(HfRemoteFile(file, 1000, null)))
            fetcher.respond(urlOf(file), status = status)
            val downloader = downloader()

            downloader.download(REPO, file)
            advanceUntilIdle()

            val row = downloader.downloads.value.single { it.file == file }
            assertEquals(DownloadState.ERROR, row.state)
            assertTrue(row.error, row.error!!.contains(phrase))
        }
    }

    @Test
    fun aCatalogFailureEndsInErrorWithItsMessage() = runTest {
        val downloader = downloader()

        downloader.download(REPO, "missing.gguf")
        advanceUntilIdle()

        val status = downloader.downloads.value.single()
        assertEquals(DownloadState.ERROR, status.state)
        assertEquals(HfError.NotFound().message, status.error)
    }

    /** Not backgroundScope: advanceUntilIdle leaves background work unrun, and the transfers are under test. */
    private fun TestScope.downloader(): Downloader {
        val downloadDispatcher = StandardTestDispatcher(testScheduler)
        return Downloader(
            source = HfFileSource(fetcher, BASE, TOKEN),
            planner = DownloadPlanner { _, file -> plans[file] ?: throw HfError.NotFound() },
            root = root,
            environment = DownloadEnvironment(
                scope = CoroutineScope(SupervisorJob() + downloadDispatcher),
                io = downloadDispatcher,
                clock = Clock { testScheduler.currentTime },
                freeBytes = { freeBytes },
            ),
            onModelInstalled = { installs++ },
        )
    }

    /** Starts a download, pauses it after three 100-byte reads, and returns its id. */
    private fun TestScope.pausePartway(etag: String?): String {
        planSingleFile()
        lateinit var first: Downloader
        lateinit var id: String
        serve(URL, CONTENT, etag) { ScriptedBody(it, onRead = { read -> if (read == 4) first.pause(id) }) }
        first = downloader()
        id = first.download(REPO, FILE).id
        advanceUntilIdle()
        check(part.length() == 300L)
        return id
    }

    private fun planSingleFile(sha256: String = sha256Of(CONTENT)) {
        plans[FILE] = HfDownloadPlan(COMMIT, listOf(HfRemoteFile(FILE, CONTENT.size.toLong(), sha256)))
    }

    private fun planAndServe(file: String) {
        plans[file] = HfDownloadPlan(COMMIT, listOf(HfRemoteFile(file, CONTENT.size.toLong(), sha256Of(CONTENT))))
        serve(urlOf(file), CONTENT)
    }

    /** Serves [content] as a server that honours `Range` (206 with a `Content-Range`) and sends [etag]. */
    private fun serve(
        url: String,
        content: ByteArray,
        etag: String? = null,
        body: (ByteArray) -> InputStream = { it.inputStream() },
    ) {
        fetcher.respondTo(url) { request ->
            val validator = etag?.let { mapOf("ETag" to it) }.orEmpty()
            val from = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            if (from == null) {
                HttpResponse(200, url, validator, body(content))
            } else {
                val range = mapOf("Content-Range" to "bytes $from-${content.size - 1}/${content.size}")
                HttpResponse(206, url, validator + range, body(content.copyOfRange(from, content.size)))
            }
        }
    }

    private fun writePart(bytes: ByteArray) {
        part.parentFile!!.mkdirs()
        part.writeBytes(bytes)
    }

    /** Serves 100 bytes per read; calls [onRead] with the read's number first, and fails read [failAtRead]. */
    private class ScriptedBody(
        private val content: ByteArray,
        private val failAtRead: Int? = null,
        private val onRead: (Int) -> Unit = {},
    ) : InputStream() {
        private var position = 0
        private var reads = 0

        override fun read(): Int = throw UnsupportedOperationException("read in chunks")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= content.size) return -1
            reads++
            onRead(reads)
            if (reads == failAtRead) throw IOException("Connection reset.")
            val count = minOf(100, length, content.size - position)
            content.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        const val BASE = "https://hf.test"
        const val TOKEN = "hf_secret"
        const val REPO = "unsloth/Qwen3-1.7B-GGUF"
        const val FILE = "Qwen3-1.7B-Q4_K_M.gguf"
        val COMMIT = "a".repeat(40)
        val URL = urlOf(FILE)
        val CONTENT = ByteArray(1000) { (it % 251).toByte() }

        fun urlOf(file: String) = "$BASE/$REPO/resolve/$COMMIT/$file"

        fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
