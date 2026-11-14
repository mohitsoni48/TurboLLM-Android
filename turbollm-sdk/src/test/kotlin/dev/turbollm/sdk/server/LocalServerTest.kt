package dev.turbollm.sdk.server

import dev.turbollm.sdk.ServerOptions
import dev.turbollm.sdk.internal.Logger
import io.ktor.server.application.Application
import io.ktor.server.cio.CIOApplicationEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The port a [FakeTransport] reports as bound. Nothing binds it. */
private const val PRETEND_BOUND_PORT = 6997

class LocalServerTest {

    private val transport = FakeTransport()
    private val logger = CapturingLogger()
    private val server = LocalServer(transport, logger)

    @Test
    fun aBlankTokenIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ServerOptions(token = "") }
        assertThrows(IllegalArgumentException::class.java) { ServerOptions(token = "   ") }
    }

    @Test
    fun theDefaultOptionsAskForAnEphemeralPortAndAGeneratedToken() {
        assertEquals(ServerOptions(port = 0, token = null), ServerOptions())
    }

    @Test
    fun aGeneratedTokenIsSixtyFourLowerCaseHexCharacters() = runTest {
        val handle = server.start(ServerOptions())

        assertTrue(handle.token, handle.token.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun twoServersNeverGenerateTheSameToken() = runTest {
        val first = server.start(ServerOptions()).token
        val second = LocalServer(FakeTransport(), logger).start(ServerOptions()).token

        assertNotEquals(first, second)
    }

    @Test
    fun aTokenTheHostPassesIsKept() = runTest {
        assertEquals("host-chosen-token", server.start(ServerOptions(token = "host-chosen-token")).token)
    }

    @Test
    fun theHandleNamesTheLoopbackAddressAndThePortActuallyBound() = runTest {
        val handle = server.start(ServerOptions())

        assertEquals(0, transport.requestedPort)
        assertEquals(PRETEND_BOUND_PORT, handle.port)
        assertEquals("http://127.0.0.1:$PRETEND_BOUND_PORT", handle.baseUrl)
    }

    @Test
    fun theLoggerNeverReceivesTheToken() = runTest {
        val generated = server.start(ServerOptions()).token
        server.stop()
        val chosen = LocalServer(FakeTransport(), logger).start(ServerOptions(token = "host-chosen-token")).token

        assertTrue("the server logs that it started", logger.lines.isNotEmpty())
        assertFalse(logger.lines.any { it.contains(generated) || it.contains(chosen) })
    }

    @Test
    fun stoppingTheServerStopsTheTransport() = runTest {
        server.start(ServerOptions())
        server.stop()

        assertTrue(transport.stopped)
    }

    @Test
    fun theCioEngineListensOnLoopbackOnlyAndDropsIdleConnectionsAfterAMinute() {
        val configuration = CIOApplicationEngine.Configuration().apply { listenOnLoopbackOnly(port = 0) }

        assertEquals(listOf("127.0.0.1"), configuration.connectors.map { it.host })
        assertEquals(listOf(0), configuration.connectors.map { it.port })
        assertEquals(60, configuration.connectionIdleTimeoutSeconds)
    }

    private class FakeTransport : HttpTransport {
        var requestedPort: Int? = null
        var stopped = false

        override suspend fun start(port: Int, module: Application.() -> Unit): Int {
            requestedPort = port
            return PRETEND_BOUND_PORT
        }

        override fun stop() {
            stopped = true
        }
    }

    private class CapturingLogger : Logger {
        val lines = mutableListOf<String>()

        override fun info(message: String) {
            lines += message
        }
    }
}
