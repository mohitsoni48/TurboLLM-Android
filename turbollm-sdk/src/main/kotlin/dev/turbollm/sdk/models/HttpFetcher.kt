package dev.turbollm.sdk.models

import java.io.Closeable
import java.io.InputStream

/** The one HTTP seam the SDK uses (Hugging Face only): a GET whose body streams. Redirects are already followed. */
internal interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** The final response after redirects. [url] is where it came from; [body] must be closed by the reader. */
internal class HttpResponse(
    val status: Int,
    val url: String,
    headers: Map<String, String>,
    val body: InputStream,
) : Closeable {
    private val headersByLowerName = headers.mapKeys { it.key.lowercase() }

    /** A response header by name, ignoring case. */
    fun header(name: String): String? = headersByLowerName[name.lowercase()]

    override fun close() = body.close()
}
