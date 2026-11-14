package dev.turbollm.sdk.models

import dev.turbollm.sdk.HfError
import dev.turbollm.sdk.HfModelFile
import dev.turbollm.sdk.HfRepo
import dev.turbollm.sdk.HfSort
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.testing.FakeHttpFetcher
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HfCatalogTest {

    private val fetcher = FakeHttpFetcher()
    private var now = 0L
    private var token: String? = null
    private val catalog by lazy { HfCatalog(fetcher, BASE, token, Clock { now }) }

    @Test
    fun aSearchMergesTheGgufAndLiteRtResultsOncePerRepo() = runTest {
        respondToSearch("qwen", HfSort.TRENDING)

        val repos = catalog.search("qwen", HfSort.TRENDING, limit = 30)

        val expected = listOf(
            HfRepo("unsloth/Qwen3-1.7B-GGUF", 1000, 50, "2026-10-01T00:00:00.000Z", gated = false, setOf(ModelFormat.GGUF)),
            HfRepo(
                "google/gemma-3-1b-it", 10, 5, "2026-09-01T00:00:00.000Z", gated = true,
                setOf(ModelFormat.GGUF, ModelFormat.LITERTLM),
            ),
            HfRepo("bartowski/Old-GGUF", 3, 1, "2025-01-01T00:00:00.000Z", gated = false, setOf(ModelFormat.GGUF)),
            HfRepo("litert-community/Gemma3-1B-IT", 500, 40, "2026-08-01T00:00:00.000Z", gated = true, setOf(ModelFormat.LITERTLM)),
        )
        assertEquals(expected, repos)
    }

    @Test
    fun eachSortMapsToTheHuggingFaceField() = runTest {
        val fields = mapOf(
            HfSort.TRENDING to "trendingScore",
            HfSort.DOWNLOADS to "downloads",
            HfSort.LIKES to "likes",
            HfSort.MODIFIED to "lastModified",
        )
        fetcher.respondByDefault(200, "[]".toByteArray())

        fields.forEach { (sort, field) ->
            catalog.search("qwen 3", sort, limit = 12)
            val expected = listOf("gguf", "litert-lm").map { filter ->
                "$BASE/api/models?search=qwen%203&filter=$filter&sort=$field&direction=-1&limit=12&full=false"
            }
            assertEquals(expected, fetcher.requests.takeLast(2).map { it.url })
        }
    }

    @Test
    fun filesListTheGgufAndLiteRtFilesOfTheCurrentCommitAcrossEveryTreePage() = runTest {
        respondToTree(nextLink = "<$BASE/api/models/$REPO/tree/$SHA?recursive=true&cursor=p2>; rel=\"next\"")

        val files = catalog.files(REPO)

        val expected = listOf(
            HfModelFile("Q8_0/Big-Q8_0-00001-of-00002.gguf", ModelFormat.GGUF, 300, "Q8_0", shards = 2, sha256 = null),
            HfModelFile("Qwen3-1.7B-Q4_K_M.gguf", ModelFormat.GGUF, 1_107_000_000, "Q4_K_M", shards = 1, sha256 = "a".repeat(64)),
            HfModelFile("model-int4.litertlm", ModelFormat.LITERTLM, 500, "INT4", shards = 1, sha256 = null),
        )
        assertEquals(expected, files)
        assertEquals(
            listOf(REVISION_URL, TREE_URL, "$TREE_URL&cursor=p2"),
            fetcher.requests.map { it.url },
        )
    }

    @Test
    fun aCrossOriginNextLinkIsNotFollowed() = runTest {
        respondToTree(nextLink = "<https://elsewhere.example/api/models/$REPO/tree/$SHA?cursor=p2>; rel=\"next\"")

        val files = catalog.files(REPO)

        assertEquals(listOf(REVISION_URL, TREE_URL), fetcher.requests.map { it.url })
        assertFalse(files.any { it.format == ModelFormat.LITERTLM })
    }

    @Test
    fun aRelativeNextLinkIsResolvedOnTheSameOrigin() = runTest {
        respondToTree(nextLink = "</api/models/$REPO/tree/$SHA?recursive=true&cursor=p2>; rel=\"next\"")

        catalog.files(REPO)

        assertEquals("$TREE_URL&cursor=p2", fetcher.requests.last().url)
    }

    @Test
    fun theTreeIsReadForAtMostFiftyPages() = runTest {
        fetcher.respond(REVISION_URL, body = fixture("revision.json"))
        (0..60).forEach { page ->
            fetcher.respond(pageUrl(page), body = "[]".toByteArray(), headers = mapOf("Link" to "<${pageUrl(page + 1)}>; rel=\"next\""))
        }

        catalog.files(REPO)

        assertEquals(50, fetcher.requests.count { it.url.startsWith(TREE_URL) })
    }

    @Test
    fun aRevisionWithoutAShaListsTheMainBranch() = runTest {
        fetcher.respond(REVISION_URL, body = "{}".toByteArray())
        fetcher.respond("$BASE/api/models/$REPO/tree/main?recursive=true", body = "[]".toByteArray())

        assertEquals(emptyList<HfModelFile>(), catalog.files(REPO))
    }

    @Test
    fun expandNamesTheCommitAndEveryShardOfTheChosenFile() = runTest {
        respondToTree(nextLink = "<$BASE/api/models/$REPO/tree/$SHA?recursive=true&cursor=p2>; rel=\"next\"")

        val plan = catalog.expand(REPO, "Q8_0/Big-Q8_0-00001-of-00002.gguf")

        assertEquals(SHA, plan.commit)
        assertEquals(
            listOf(
                HfRemoteFile("Q8_0/Big-Q8_0-00001-of-00002.gguf", 100, "b".repeat(64)),
                HfRemoteFile("Q8_0/Big-Q8_0-00002-of-00002.gguf", 200, "d".repeat(64)),
            ),
            plan.files,
        )
    }

    @Test
    fun expandingAFileTheRepoDoesNotHaveIsNotFound() = runTest {
        respondToTree(nextLink = null)

        val error = runCatching { catalog.expand(REPO, "missing.gguf") }.exceptionOrNull()

        assertTrue("got $error", error is HfError.NotFound)
    }

    @Test
    fun aRepeatedRequestWithinFiveMinutesIsServedFromTheCache() = runTest {
        respondToSearch("qwen", HfSort.LIKES)
        catalog.search("qwen", HfSort.LIKES, limit = 30)
        val afterFirst = fetcher.requests.size

        now += 299_999
        catalog.search("qwen", HfSort.LIKES, limit = 30)
        assertEquals(afterFirst, fetcher.requests.size)

        now += 1
        catalog.search("qwen", HfSort.LIKES, limit = 30)
        assertEquals(afterFirst * 2, fetcher.requests.size)
    }

    @Test
    fun theCacheKeepsAtMostTwoHundredEntriesDroppingTheOldestFirst() = runTest {
        fetcher.respondByDefault(200, "[]".toByteArray())
        catalog.search("q0", HfSort.TRENDING, limit = 30)
        (1..99).forEach { catalog.search("q$it", HfSort.TRENDING, limit = 30) }
        catalog.search("q99", HfSort.TRENDING, limit = 30)
        val beforeOverflow = fetcher.requests.size

        catalog.search("q100", HfSort.TRENDING, limit = 30)
        catalog.search("q1", HfSort.TRENDING, limit = 30)
        assertEquals("q1 is still cached", beforeOverflow + 2, fetcher.requests.size)

        catalog.search("q0", HfSort.TRENDING, limit = 30)
        assertEquals("q0 was evicted", beforeOverflow + 4, fetcher.requests.size)
    }

    @Test
    fun httpStatusesMapToTypedErrors() = runTest {
        val expected = mapOf(401 to HfError.AuthRequired::class, 403 to HfError.Gated::class, 404 to HfError.NotFound::class, 500 to HfError.Unreachable::class)

        expected.forEach { (status, type) ->
            fetcher.respond(REVISION_URL, status = status)
            now += 1_000_000
            val error = runCatching { catalog.files(REPO) }.exceptionOrNull()
            assertTrue("HTTP $status gave $error", type.isInstance(error))
        }
    }

    @Test
    fun anUnreachableHuggingFaceIsTypedUnreachable() = runTest {
        fetcher.fail(REVISION_URL, IOException("no route to host"))

        val error = runCatching { catalog.files(REPO) }.exceptionOrNull()

        assertTrue("got $error", error is HfError.Unreachable)
    }

    @Test
    fun theTokenIsSentToHuggingFaceAsABearer() = runTest {
        token = "hf_secret"
        respondToTree(nextLink = "<$BASE/api/models/$REPO/tree/$SHA?recursive=true&cursor=p2>; rel=\"next\"")

        catalog.files(REPO)

        assertEquals(setOf("Bearer hf_secret"), fetcher.requests.map { it.headers["Authorization"] }.toSet())
    }

    @Test
    fun noAuthorizationIsSentWithoutAToken() = runTest {
        token = "  "
        respondToSearch("qwen", HfSort.TRENDING)

        catalog.search("qwen", HfSort.TRENDING, limit = 30)

        assertTrue(fetcher.requests.none { "Authorization" in it.headers })
    }

    private fun respondToSearch(query: String, sort: HfSort) {
        val field = mapOf(
            HfSort.TRENDING to "trendingScore",
            HfSort.DOWNLOADS to "downloads",
            HfSort.LIKES to "likes",
            HfSort.MODIFIED to "lastModified",
        ).getValue(sort)
        val searchUrl = "$BASE/api/models?search=$query&filter=%s&sort=$field&direction=-1&limit=30&full=false"
        fetcher.respond(searchUrl.format("gguf"), body = fixture("search-gguf.json"))
        fetcher.respond(searchUrl.format("litert-lm"), body = fixture("search-litertlm.json"))
    }

    private fun respondToTree(nextLink: String?) {
        fetcher.respond(REVISION_URL, body = fixture("revision.json"))
        val pageOneHeaders = nextLink?.let { mapOf("Link" to it) }.orEmpty()
        fetcher.respond(TREE_URL, body = fixture("tree-page1.json"), headers = pageOneHeaders)
        fetcher.respond("$TREE_URL&cursor=p2", body = fixture("tree-page2.json"))
    }

    private fun pageUrl(page: Int) = if (page == 0) TREE_URL else "$TREE_URL&cursor=$page"

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hf/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private companion object {
        const val BASE = "https://huggingface.co"
        const val REPO = "unsloth/Qwen3-1.7B-GGUF"
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
        const val REVISION_URL = "$BASE/api/models/$REPO/revision/main?expand%5B%5D=sha"
        const val TREE_URL = "$BASE/api/models/$REPO/tree/$SHA?recursive=true"
    }
}
