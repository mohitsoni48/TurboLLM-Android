package dev.turbollm.sdk.models

import dev.turbollm.sdk.HfError
import dev.turbollm.sdk.HfModelFile
import dev.turbollm.sdk.HfRepo
import dev.turbollm.sdk.HfSort
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.sdkJson
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val CACHE_TTL_MILLIS = 5 * 60 * 1_000L
private const val CACHE_MAX_ENTRIES = 200
private const val TREE_MAX_PAGES = 50
private const val MAIN_BRANCH = "main"
private val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
private val GATED_MODES = setOf("auto", "manual")

/** The commit a download targets and every file (shard) it must fetch. */
internal data class HfDownloadPlan(val commit: String, val files: List<HfRemoteFile>)

/**
 * Hugging Face search, revision and tree listing, filtered to the formats the SDK runs; a port of the daemon's
 * `turbollm/src/hf/hf.ts` (`HfClient`). The token goes to [baseUrl] only: the fetcher drops it on a
 * cross-origin redirect, and a tree page link to another origin is not followed. Responses are cached for five
 * minutes, at most [CACHE_MAX_ENTRIES] of them, the oldest written dropped first.
 */
internal class HfCatalog(
    private val fetcher: HttpFetcher,
    baseUrl: String,
    token: String?,
    private val clock: Clock,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val authorization =
        token?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") }
    private val cache = LinkedHashMap<String, CachedJson>()

    suspend fun search(query: String, sort: HfSort, limit: Int): List<HfRepo> {
        val gguf = searchRows(query, "gguf", sort, limit)
        val liteRt = searchRows(query, "litert-lm", sort, limit)
        return mergedByRepo(listOf(ModelFormat.GGUF to gguf, ModelFormat.LITERTLM to liteRt))
    }

    suspend fun files(repo: String): List<HfModelFile> = treeAt(repo, commitOf(repo)).modelFiles()

    /** The commit and the files to download for the model at [path] (its first shard, for a split GGUF). */
    suspend fun expand(repo: String, path: String): HfDownloadPlan {
        val commit = commitOf(repo)
        val files = treeAt(repo, commit).filesOf(path) ?: throw HfError.NotFound("$repo has no model file '$path'.")
        return HfDownloadPlan(commit, files)
    }

    private suspend fun searchRows(query: String, filter: String, sort: HfSort, limit: Int): List<RawSearchItem> {
        val url = "$baseUrl/api/models?search=${encodeQuery(query.trim())}&filter=$filter" +
            "&sort=${sort.field}&direction=-1&limit=$limit&full=false"
        return decode(ListSerializer(RawSearchItem.serializer()), cachedJson(url, maxPages = 1))
    }

    private suspend fun commitOf(repo: String): String {
        val url = "$baseUrl/api/models/$repo/revision/$MAIN_BRANCH?expand%5B%5D=sha"
        val sha = decode(RawRevision.serializer(), cachedJson(url, maxPages = 1)).sha
        return sha?.takeIf { COMMIT_SHA.matches(it) } ?: MAIN_BRANCH
    }

    private suspend fun treeAt(repo: String, commit: String): HfTree {
        val url = "$baseUrl/api/models/$repo/tree/$commit?recursive=true"
        return HfTree(decode(ListSerializer(RawTreeEntry.serializer()), cachedJson(url, TREE_MAX_PAGES)))
    }

    private suspend fun cachedJson(url: String, maxPages: Int): JsonElement {
        freshCached(url)?.let { return it }
        val value = if (maxPages == 1) fetchPage(url).json else fetchPages(url, maxPages)
        remember(url, value)
        return value
    }

    /** Follows same-origin `Link: rel="next"` pages, up to [maxPages], into one array. */
    private suspend fun fetchPages(firstUrl: String, maxPages: Int): JsonArray {
        val entries = mutableListOf<JsonElement>()
        var next: String? = firstUrl
        var pages = 0
        while (next != null && pages < maxPages) {
            val page = fetchPage(next)
            entries.addAll(page.json as? JsonArray ?: throw unexpectedResponse())
            next = page.next
            pages++
        }
        return JsonArray(entries)
    }

    private suspend fun fetchPage(url: String): Page {
        val response = try {
            fetcher.get(url, authorization.orEmpty())
        } catch (unreachable: IOException) {
            throw HfError.Unreachable("Hugging Face is unreachable; check the connection.", unreachable)
        }
        return response.use {
            failureFor(it.status)?.let { failure -> throw failure }
            Page(parseJson(it), nextPageUrl(it.header("Link"), url))
        }
    }

    private suspend fun parseJson(response: HttpResponse): JsonElement = withContext(Dispatchers.IO) {
        try {
            sdkJson.parseToJsonElement(response.body.readBytes().decodeToString())
        } catch (malformed: SerializationException) {
            throw unexpectedResponse(malformed)
        } catch (interrupted: IOException) {
            throw HfError.Unreachable("Hugging Face closed the connection mid-response.", interrupted)
        }
    }

    private fun freshCached(url: String): JsonElement? = synchronized(cache) {
        cache[url]?.takeIf { clock.nowMillis() - it.storedAt < CACHE_TTL_MILLIS }?.value
    }

    private fun remember(url: String, value: JsonElement) = synchronized(cache) {
        cache.remove(url)
        cache[url] = CachedJson(clock.nowMillis(), value)
        while (cache.size > CACHE_MAX_ENTRIES) cache.remove(cache.keys.first())
    }

    private class CachedJson(val storedAt: Long, val value: JsonElement)

    private class Page(val json: JsonElement, val next: String?)
}

@Serializable
private data class RawSearchItem(
    val id: String? = null,
    val modelId: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    val lastModified: String? = null,
    val createdAt: String? = null,
    val gated: JsonElement? = null,
)

@Serializable
private data class RawRevision(val sha: String? = null)

private val HfSort.field: String
    get() = when (this) {
        HfSort.TRENDING -> "trendingScore"
        HfSort.DOWNLOADS -> "downloads"
        HfSort.LIKES -> "likes"
        HfSort.MODIFIED -> "lastModified"
    }

/** Each repo once, in the order first seen, with every format whose search found it. */
private fun mergedByRepo(results: List<Pair<ModelFormat, List<RawSearchItem>>>): List<HfRepo> {
    val merged = LinkedHashMap<String, HfRepo>()
    results.forEach { (format, rows) ->
        rows.mapNotNull { it.toHfRepo(format) }.forEach { repo ->
            merged[repo.repo] = merged[repo.repo]?.let { it.copy(formats = it.formats + format) } ?: repo
        }
    }
    return merged.values.toList()
}

private fun RawSearchItem.toHfRepo(format: ModelFormat): HfRepo? {
    val repo = (id ?: modelId)?.takeIf { it.isNotBlank() } ?: return null
    return HfRepo(
        repo = repo,
        downloads = downloads ?: 0,
        likes = likes ?: 0,
        updatedAt = lastModified ?: createdAt,
        gated = isGated(gated),
        formats = setOf(format),
    )
}

/** Hugging Face reports a gated repo as `true`, `"auto"` or `"manual"`, an open one as `false`. */
private fun isGated(gated: JsonElement?): Boolean {
    val mode = gated as? JsonPrimitive ?: return false
    return if (mode.isString) mode.content in GATED_MODES else mode.content == "true"
}

private fun failureFor(status: Int): HfError? = when (status) {
    in 200..299 -> null
    401 -> HfError.AuthRequired()
    403 -> HfError.Gated()
    404 -> HfError.NotFound()
    else -> HfError.Unreachable("Hugging Face request failed (HTTP $status).")
}

private fun <T> decode(deserializer: DeserializationStrategy<T>, json: JsonElement): T = try {
    sdkJson.decodeFromJsonElement(deserializer, json)
} catch (malformed: SerializationException) {
    throw unexpectedResponse(malformed)
}

private fun unexpectedResponse(cause: Throwable? = null) =
    HfError.Unreachable("Hugging Face sent a response the SDK does not understand.", cause)

/** `encodeURIComponent`-style: a space is `%20`, as the daemon sends it, not `+`. */
private fun encodeQuery(query: String): String = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
