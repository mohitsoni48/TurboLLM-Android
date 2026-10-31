package dev.turbollm.sdk.models

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlConnectionFetcherRedirectTest {

    private val opened = mutableListOf<ScriptedConnection>()
    private val routes = mutableMapOf<String, Route>()
    private val fetcher = UrlConnectionFetcher(openConnection = ::open)

    @Test
    fun aSameOriginRedirectKeepsTheAuthorization() = runTest {
        redirect("https://huggingface.co/a", "https://huggingface.co/b")
        answer("https://huggingface.co/b", "ok")

        val response = fetcher.get("https://huggingface.co/a", mapOf(AUTHORIZATION to TOKEN))

        assertEquals(listOf(TOKEN, TOKEN), opened.map { it.getRequestProperty(AUTHORIZATION) })
        assertEquals("https://huggingface.co/b", response.url)
        assertEquals("ok", response.body.use { it.readBytes().decodeToString() })
    }

    @Test
    fun aCrossOriginRedirectDropsTheCredentialsButKeepsTheOtherHeaders() = runTest {
        redirect("https://huggingface.co/a", "https://cdn-lfs.hf.co/file")
        answer("https://cdn-lfs.hf.co/file", "bytes")
        val headers = mapOf(AUTHORIZATION to TOKEN, "Cookie" to "c=1", "Range" to "bytes=10-")

        fetcher.get("https://huggingface.co/a", headers)

        val cdn = opened.last()
        assertNull(cdn.getRequestProperty(AUTHORIZATION))
        assertNull(cdn.getRequestProperty("Cookie"))
        assertEquals("bytes=10-", cdn.getRequestProperty("Range"))
    }

    @Test
    fun aRedirectToAnotherPortOrSchemeIsCrossOrigin() = runTest {
        redirect("https://huggingface.co/a", "https://huggingface.co:8443/b")
        redirect("https://huggingface.co:8443/b", "http://huggingface.co:8443/c")
        answer("http://huggingface.co:8443/c", "ok")

        fetcher.get("https://huggingface.co/a", mapOf(AUTHORIZATION to TOKEN))

        assertEquals(listOf(TOKEN, null, null), opened.map { it.getRequestProperty(AUTHORIZATION) })
    }

    @Test
    fun theDefaultPortWrittenOutIsTheSameOrigin() = runTest {
        redirect("https://huggingface.co/a", "https://HuggingFace.co:443/b")
        answer("https://HuggingFace.co:443/b", "ok")

        fetcher.get("https://huggingface.co/a", mapOf(AUTHORIZATION to TOKEN))

        assertEquals(TOKEN, opened.last().getRequestProperty(AUTHORIZATION))
    }

    @Test
    fun twentyRedirectsAreFollowed() = runTest {
        chainOfRedirects(20)

        val response = fetcher.get(hop(0), emptyMap())

        assertEquals(200, response.status)
        assertEquals(21, opened.size)
    }

    @Test
    fun theTwentyFirstRedirectFails() = runTest {
        chainOfRedirects(21)

        val failure = runCatching { fetcher.get(hop(0), emptyMap()) }.exceptionOrNull()

        assertTrue("got $failure", failure is IOException)
        assertEquals("Too many redirects (more than 20).", failure?.message)
        assertEquals(21, opened.size)
    }

    @Test
    fun aRelativeLocationIsResolvedAgainstTheCurrentUrl() = runTest {
        redirect("https://huggingface.co/org/repo/resolve/main/m.gguf", "../blob/x.gguf")
        redirect("https://huggingface.co/org/repo/resolve/blob/x.gguf", "/final?y=1")
        answer("https://huggingface.co/final?y=1", "ok")

        val response = fetcher.get("https://huggingface.co/org/repo/resolve/main/m.gguf", emptyMap())

        assertEquals("https://huggingface.co/final?y=1", response.url)
    }

    @Test
    fun aRedirectToANonHttpUrlFails() = runTest {
        redirect("https://huggingface.co/a", "file:///etc/passwd")

        val failure = runCatching { fetcher.get("https://huggingface.co/a", emptyMap()) }.exceptionOrNull()

        assertTrue("got $failure", failure is IOException)
        assertEquals(1, opened.size)
    }

    @Test
    fun theConnectionNeverFollowsRedirectsOnItsOwn() = runTest {
        answer("https://huggingface.co/a", "ok")

        fetcher.get("https://huggingface.co/a", emptyMap())

        assertFalse(opened.single().instanceFollowRedirects)
        assertEquals("GET", opened.single().requestMethod)
    }

    @Test
    fun anErrorStatusIsReturnedWithItsBodyAndHeaders() = runTest {
        routes["https://huggingface.co/a"] = Route(404, body = "missing", headers = mapOf("X-Error-Code" to "RepoNotFound"))

        val response = fetcher.get("https://huggingface.co/a", emptyMap())

        assertEquals(404, response.status)
        assertEquals("RepoNotFound", response.header("x-error-code"))
        assertEquals("missing", response.body.use { it.readBytes().decodeToString() })
    }

    @Test
    fun everyRedirectConnectionIsReleased() = runTest {
        redirect("https://huggingface.co/a", "https://cdn-lfs.hf.co/b")
        answer("https://cdn-lfs.hf.co/b", "ok")

        fetcher.get("https://huggingface.co/a", emptyMap())

        assertEquals(listOf(true, false), opened.map { it.disconnected })
    }

    private fun chainOfRedirects(count: Int) {
        (0 until count).forEach { redirect(hop(it), hop(it + 1)) }
        answer(hop(count), "end")
    }

    private fun hop(index: Int) = "https://huggingface.co/hop/$index"

    private fun redirect(from: String, to: String) {
        routes[from] = Route(302, headers = mapOf("Location" to to))
    }

    private fun answer(url: String, body: String) {
        routes[url] = Route(200, body = body)
    }

    private fun open(url: URL): HttpURLConnection {
        val route = checkNotNull(routes[url.toString()]) { "no route for $url" }
        return ScriptedConnection(url, route).also { opened += it }
    }

    private data class Route(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private class ScriptedConnection(url: URL, private val route: Route) : HttpURLConnection(url) {
        var disconnected = false
            private set

        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy() = false
        override fun getResponseCode() = route.status
        override fun getHeaderField(name: String?): String? =
            route.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun getHeaderFields(): Map<String?, List<String>> =
            route.headers.mapValues { listOf(it.value) } + mapOf(null to listOf("HTTP/1.1 ${route.status}"))
        override fun getInputStream(): InputStream {
            if (route.status >= 400) throw IOException("HTTP ${route.status}")
            return ByteArrayInputStream(route.body.toByteArray())
        }
        override fun getErrorStream(): InputStream? =
            if (route.status >= 400) ByteArrayInputStream(route.body.toByteArray()) else null
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val TOKEN = "Bearer hf_test"
    }
}
