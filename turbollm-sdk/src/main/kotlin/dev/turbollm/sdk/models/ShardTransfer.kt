package dev.turbollm.sdk.models

import dev.turbollm.sdk.DownloadError
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val PART_SUFFIX = ".part"
private const val WEAK_ETAG_PREFIX = "W/"
private val CONTENT_RANGE = Regex("""^bytes[\s=]+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
private val HTTP_DATE = Regex("""^[A-Za-z]{3,9},? .*\d{2}:\d{2}:\d{2}""")

/** One file of a model download: [shard] of [repo] at [commit], written to [dest] through its `.part`. */
internal class ShardTarget(val repo: String, val commit: String, val shard: HfRemoteFile, val dest: File) {
    val part = File(dest.path + PART_SUFFIX)

    fun isInstalled(): Boolean = dest.isFile && (shard.sizeBytes <= 0 || dest.length() == shard.sizeBytes)

    /** The bytes this shard still needs on disk. */
    fun bytesStillNeeded(): Long = if (isInstalled()) 0 else (shard.sizeBytes - part.length()).coerceAtLeast(0)

    /** The bytes of this shard already on disk, finished or partial. */
    fun bytesOnDisk(): Long = if (isInstalled()) dest.length() else part.length()
}

/** What a transfer reports while it runs, so the downloader can show progress, persist and stop it. */
internal interface TransferListener {
    fun onResponse(response: HttpResponse)
    fun onValidator(validator: String?)
    fun onShardBytes(bytes: Long)
}

/** Hugging Face's `resolve` endpoint. The token goes to [baseUrl]; the fetcher drops it on a cross-origin hop. */
internal class HfFileSource(private val fetcher: HttpFetcher, baseUrl: String, token: String?) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val authorization =
        token?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    /** `identity`, so the size and sha256 checks see the file's own bytes and not a gzip of them. */
    suspend fun open(target: ShardTarget, resume: ResumePoint?): HttpResponse {
        val headers = authorization + ("Accept-Encoding" to "identity") + resume?.headers().orEmpty()
        return fetcher.get(resolveUrl(target), headers)
    }

    private fun resolveUrl(target: ShardTarget): String =
        "$baseUrl/${encodePath(target.repo)}/resolve/${encodeSegment(target.commit)}/${encodePath(target.shard.path)}"
}

/** Continue a `.part` from [offset]; [validator] is sent as `If-Range` only when it is a strong one. */
internal class ResumePoint(private val offset: Long, private val validator: String?) {
    fun headers(): Map<String, String> =
        mapOf("Range" to "bytes=$offset-") + validator?.let { mapOf("If-Range" to it) }.orEmpty()
}

/**
 * Fetches one shard into its `.part`, resuming it when one is on disk, verifies its size and sha256, then
 * renames it into place. A port of the daemon's `DownloadManager.run` (`downloads.ts`): a 200 to a Range
 * request restarts, a 416 finishes a `.part` that already holds the whole file and otherwise restarts once,
 * and a 206 that does not continue the `.part` deletes it and fails.
 */
internal class ShardTransfer(
    private val source: HfFileSource,
    private val target: ShardTarget,
    private val listener: TransferListener,
) {
    private val part = target.part
    private val expectedSha256 = target.shard.sha256

    suspend fun fetch(storedValidator: String?) {
        val startAt = part.length()
        val prefixHash = hashOfPart()
        val response = source.open(target, ResumePoint(startAt, storedValidator).takeIf { startAt > 0 })
        if (response.status == HTTP_RANGE_NOT_SATISFIABLE && startAt > 0) {
            response.close()
            if (!holdsTheWholeFile(startAt, prefixHash)) restartFromZero()
        } else {
            receive(response, startAt, prefixHash, storedValidator)
        }
        install()
    }

    private suspend fun restartFromZero() {
        Files.deleteIfExists(part.toPath())
        listener.onValidator(null)
        receive(source.open(target, resume = null), startAt = 0, prefixHash = newHash(), storedValidator = null)
    }

    private suspend fun receive(
        response: HttpResponse,
        startAt: Long,
        prefixHash: MessageDigest?,
        storedValidator: String?,
    ) = response.use {
        listener.onResponse(it)
        failOnRefusal(it.status)
        val resuming = it.status == HTTP_PARTIAL_CONTENT
        if (resuming) requireContinuesPart(it, startAt, storedValidator)
        listener.onValidator(strongValidatorOf(it) ?: storedValidator.takeIf { resuming })
        val hash = if (resuming) prefixHash else newHash()
        val received = copyToPart(it.body, if (resuming) startAt else 0, hash)
        verify(received, hash)
    }

    private fun requireContinuesPart(response: HttpResponse, startAt: Long, storedValidator: String?) {
        val changed = storedValidator != null && validatorChanged(storedValidator, response)
        if (changed || !rangeContinues(response.header("Content-Range"), startAt, target.shard.sizeBytes)) {
            Files.deleteIfExists(part.toPath())
            throw DownloadError(
                "The server's reply does not continue the partial file (the file may have changed on the server). " +
                    "Removed the partial file; resume to download it again.",
            )
        }
    }

    private suspend fun copyToPart(body: InputStream, offset: Long, hash: MessageDigest?): Long {
        target.dest.parentFile?.mkdirs()
        var received = offset
        FileOutputStream(part, offset > 0).use { out ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = body.read(buffer)
                if (count < 0) break
                currentCoroutineContext().ensureActive()
                out.write(buffer, 0, count)
                hash?.update(buffer, 0, count)
                received += count
                listener.onShardBytes(received)
            }
        }
        return received
    }

    private fun verify(received: Long, hash: MessageDigest?) {
        val size = target.shard.sizeBytes
        if (size > 0 && received != size) {
            Files.deleteIfExists(part.toPath())
            throw DownloadError(
                "Download corrupt: the size did not match ($received of $size bytes). " +
                    "Removed the partial file; resume to download it again.",
            )
        }
        if (hash != null && hash.hex() != expectedSha256) {
            Files.deleteIfExists(part.toPath())
            throw DownloadError(
                "Checksum failed: the downloaded file was corrupt. " +
                    "Removed the partial file; resume to download it again.",
            )
        }
    }

    /** A 416 means no byte past the `.part` exists: it is the file when its size and sha256 say so. */
    private fun holdsTheWholeFile(startAt: Long, prefixHash: MessageDigest?): Boolean {
        val size = target.shard.sizeBytes
        return prefixHash != null && (size <= 0 || startAt == size) && prefixHash.hex() == expectedSha256
    }

    private fun install() {
        Files.move(part.toPath(), target.dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** The sha256 of the `.part` so far (re-hashed on resume), or null when the file has no known hash. */
    private fun hashOfPart(): MessageDigest? {
        val hash = newHash() ?: return null
        if (part.length() == 0L) return hash
        FileInputStream(part).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash
    }

    private fun newHash(): MessageDigest? = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
}

private fun failOnRefusal(status: Int) {
    when {
        status == HTTP_UNAUTHORIZED -> throw DownloadError("Hugging Face rejected the token for this repository.")
        status == HTTP_FORBIDDEN ->
            throw DownloadError("This repository is gated; accept its license on huggingface.co and set a token.")
        status !in 200..299 -> throw DownloadError("Download failed (HTTP $status).")
    }
}

/**
 * The validator `If-Range` may carry (RFC 9110 §13.1.5): a strong ETag, or Last-Modified when there is no ETag.
 * A weak ETag yields none, and with one present a date may not stand in for it.
 */
internal fun strongValidatorOf(response: HttpResponse): String? {
    val etag = response.header("ETag")?.trim()
    if (!etag.isNullOrEmpty()) return etag.takeUnless { it.startsWith(WEAK_ETAG_PREFIX) }
    return response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
}

/** True when a 206 names another version than [stored]; a date is compared with a date, an ETag with an ETag. */
internal fun validatorChanged(stored: String, response: HttpResponse): Boolean {
    val theirs = if (HTTP_DATE.containsMatchIn(stored)) {
        response.header("Last-Modified")?.trim()
    } else {
        response.header("ETag")?.trim()?.takeUnless { it.startsWith(WEAK_ETAG_PREFIX) }
    }
    return !theirs.isNullOrEmpty() && theirs != stored
}

/**
 * True when a 206's Content-Range continues the `.part` to the end of the file: it starts at [startAt], names
 * the expected full length when both sides know it, and ends at the last byte. A missing or unreadable header
 * cannot be checked and is let through, as the daemon does; the size and sha256 checks still apply.
 */
internal fun rangeContinues(contentRange: String?, startAt: Long, total: Long): Boolean {
    val match = contentRange?.trim()?.let(CONTENT_RANGE::matchEntire) ?: return true
    val (first, last, full) = match.destructured
    if (first.toLongOrNull() != startAt) return false
    val fullLength = if (full == "*") total else full.toLongOrNull() ?: return true
    if (total > 0 && fullLength != total) return false
    return fullLength <= 0 || last.toLongOrNull() == fullLength - 1
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

private fun encodePath(path: String): String = path.split('/').joinToString("/", transform = ::encodeSegment)

/** `encodeURIComponent`-style, as the daemon's `hfResolveUrl` builds it: a space is `%20`, not `+`. */
private fun encodeSegment(segment: String): String =
    URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
