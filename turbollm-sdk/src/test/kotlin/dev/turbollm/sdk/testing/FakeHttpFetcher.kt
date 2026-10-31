package dev.turbollm.sdk.testing

import dev.turbollm.sdk.models.HttpFetcher
import dev.turbollm.sdk.models.HttpResponse
import java.io.ByteArrayInputStream

/**
 * A scripted [HttpFetcher]: answers each URL with what [respond] set, or [fail]s it, or falls back to the
 * [respondByDefault] reply (a 404 unless set). Records every request.
 */
internal class FakeHttpFetcher : HttpFetcher {

    data class Request(val url: String, val headers: Map<String, String>)

    private class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private val replies = mutableMapOf<String, Reply>()
    private val failures = mutableMapOf<String, Throwable>()
    private var defaultReply = Reply(404, ByteArray(0), emptyMap())
    private val recordedRequests = mutableListOf<Request>()
    val requests: List<Request> get() = recordedRequests.toList()

    fun respond(url: String, status: Int = 200, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        replies[url] = Reply(status, body, headers)
    }

    fun respondByDefault(status: Int, body: ByteArray) {
        defaultReply = Reply(status, body, emptyMap())
    }

    fun fail(url: String, error: Throwable) {
        failures[url] = error
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        recordedRequests += Request(url, headers)
        failures[url]?.let { throw it }
        val reply = replies[url] ?: defaultReply
        return HttpResponse(reply.status, url, reply.headers, ByteArrayInputStream(reply.body))
    }
}
