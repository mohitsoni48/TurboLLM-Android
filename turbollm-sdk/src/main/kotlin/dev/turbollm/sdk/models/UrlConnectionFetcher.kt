package dev.turbollm.sdk.models

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** fetch's redirect limit (WHATWG Fetch), as the TurboLLM daemon's `http-get.ts` keeps it. */
private const val MAX_REDIRECTS = 20
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
private val HTTP_SCHEMES = setOf("http", "https")

/** What a redirect to another origin must not carry: an HF token never reaches the CDN or any other host. */
private val CROSS_ORIGIN_DROPPED = setOf("authorization", "proxy-authorization", "cookie", "host")

private const val CONNECT_TIMEOUT_MILLIS = 30_000
private const val READ_TIMEOUT_MILLIS = 60_000

/**
 * [HttpFetcher] over `HttpURLConnection` (no new dependency). Redirects are followed by hand, at most
 * [MAX_REDIRECTS], so credentials can be dropped on a hop that changes origin (scheme, host or port).
 */
internal class UrlConnectionFetcher(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HttpFetcher {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) { followRedirects(URL(url), headers) }

    private fun followRedirects(start: URL, headers: Map<String, String>): HttpResponse {
        var current = start
        var sent = headers
        repeat(MAX_REDIRECTS + 1) {
            val connection = send(current, sent)
            val location = connection.redirectLocation() ?: return connection.toResponse(current)
            connection.disconnect()
            val next = redirectTarget(current, location)
            if (!next.hasSameOriginAs(current)) sent = sent.withoutCredentials()
            current = next
        }
        throw IOException("Too many redirects (more than $MAX_REDIRECTS).")
    }

    private fun send(url: URL, headers: Map<String, String>): HttpURLConnection =
        openConnection(url).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
}

private fun HttpURLConnection.redirectLocation(): String? =
    if (responseCode in REDIRECT_STATUSES) getHeaderField("Location") else null

private fun redirectTarget(current: URL, location: String): URL {
    val next = try {
        URL(current, location)
    } catch (invalid: MalformedURLException) {
        throw IOException("${current.host} redirected to an invalid URL.", invalid)
    }
    if (next.protocol.lowercase() !in HTTP_SCHEMES) throw IOException("${current.host} redirected to a non-HTTP URL.")
    return next
}

internal fun URL.hasSameOriginAs(other: URL): Boolean =
    protocol.equals(other.protocol, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

internal fun URL.effectivePort(): Int = if (port == -1) defaultPort else port

private fun Map<String, String>.withoutCredentials(): Map<String, String> =
    filterKeys { it.lowercase() !in CROSS_ORIGIN_DROPPED }

private fun HttpURLConnection.toResponse(url: URL): HttpResponse {
    val status = responseCode
    return HttpResponse(status, url.toString(), singleValuedHeaders(), bodyFor(status))
}

private fun HttpURLConnection.singleValuedHeaders(): Map<String, String> =
    headerFields.entries
        .mapNotNull { (name, values) -> name?.let { it to values.joinToString(", ") } }
        .toMap()

private fun HttpURLConnection.bodyFor(status: Int): InputStream =
    if (status >= HttpURLConnection.HTTP_BAD_REQUEST) errorStream ?: ByteArrayInputStream(ByteArray(0)) else inputStream
