package dev.turbollm.sdk.testing

import dev.turbollm.sdk.models.HttpFetcher
import dev.turbollm.sdk.models.HttpResponse
import java.io.ByteArrayInputStream

/** A scripted [HttpFetcher]: answers each URL with what [respond] set (404 otherwise) and records every request. */
internal class FakeHttpFetcher : HttpFetcher {

    data class Request(val url: String, val headers: Map<String, String>)

    private class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private val replies = mutableMapOf<String, Reply>()
    private val recordedRequests = mutableListOf<Request>()
    val requests: List<Request> get() = recordedRequests.toList()

    fun respond(url: String, status: Int = 200, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        replies[url] = Reply(status, body, headers)
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        recordedRequests += Request(url, headers)
        val reply = replies[url] ?: Reply(404, ByteArray(0), emptyMap())
        return HttpResponse(reply.status, url, reply.headers, ByteArrayInputStream(reply.body))
    }
}
