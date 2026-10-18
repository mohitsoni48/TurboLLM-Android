package com.turbollm.app

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The only way native code reaches the daemon with DAEMON_APP_SECRET, so the secret goes nowhere but to the daemon
 *  this process started. Native code must not open any other connection to the daemon's port (see the last paragraph).
 *
 *  Any app on the phone can listen on 127.0.0.1:ANDROID_DAEMON_PORT while our daemon does not: before it binds at
 *  launch (EngineService polls from the start, and the daemon retries a busy port for about 10 s), or for a moment
 *  while a LAN toggle rebinds it. A secret sent there would let that app use the whole API for as long as this process
 *  lives. So before each request the port's holder must answer a fresh random nonce with HMAC-SHA256 under the secret
 *  (GET /api/v1/app-proof, daemon auth.ts appSecretProof), which only our daemon can do and which is no use as the
 *  secret itself. A proof per request rather than one kept until a request fails: an app that took the port over
 *  between two requests would answer the second one, not fail it.
 *
 *  A proof vouches only for the listener, so the proof and the request each go out on a new socket, never on a pooled
 *  one: the proof on a plain socket of its own (see PROOF_DEADLINE_MS), the request with Connection: close. Android's
 *  HttpURLConnection keeps keep-alive sockets in one pool for the process and reuses them even after disconnect(), and
 *  a pooled socket stays connected to whoever accepted it: an app holding the port at launch could let our daemon have
 *  it, keep the socket we pooled, pass each nonce on to the daemon, and receive the request that follows on that
 *  socket. With every socket to the port closed after its one request, nothing to it is ever in the pool. What this
 *  guarantees: the secret goes out only on a connection opened just after our daemon, on another new connection, proved
 *  it held the port. It reaches another app only if the daemon gave the port up in between, i.e. a LAN-toggle rebind;
 *  an app that is still on the port when the daemon binds again makes the daemon exit (cli.ts), so what is left is an
 *  app that binds and lets go inside that gap of a few ms, just as a request is made.
 *
 *  Both connections also go straight to the port, never through the system HTTP proxy (Proxy.NO_PROXY). Android
 *  exempts 127.0.0.1 from that proxy only when the proxy's bypass list is not empty, and it often is empty: a Wi-Fi
 *  proxy's "Bypass proxy for" starts empty, and so does the list of the proxy a VPN app sets with
 *  VpnService.Builder.setHttpProxy. A proxy app on the phone would then carry both connections: it could pass the
 *  nonce on to our daemon, which proves itself, and read the request that follows, secret and all. */
object DaemonAuth {
    private const val HOST = "127.0.0.1"
    private const val BASE = "http://$HOST:$ANDROID_DAEMON_PORT"
    private val random = SecureRandom()

    // Until its answer checks out, the port's holder may be another app, and nothing it sends may stall or crash us.
    // Socket timeouts are per read, so an answer trickled a byte at a time would keep the splash and the EngineService
    // poll waiting as long as the sender liked: hence one deadline for the whole request. An endless or deeply nested
    // answer would run a plain read or a recursive JSON parser out of memory or stack, an Error: hence a cap on what
    // is read, a regex rather than a parse, and catching Throwable. The cap covers the whole answer, status line and
    // headers included, so the request is written by hand on a plain socket: HttpURLConnection reads the status line
    // and headers whole before it hands over the body, with no limit, and header lines sent as fast as loopback goes
    // filled a 96 MiB heap in under 2 s, well inside the deadline. The daemon's answer is about 230 bytes, its body
    // {"proof":"<64 hex>"} 76.
    private const val PROOF_DEADLINE_MS = 3000L
    private const val PROOF_MAX_BYTES = 4096
    private val PROOF = Regex("\"proof\"\\s*:\\s*\"([0-9a-f]{64})\"")
    private val STATUS_OK = Regex("^HTTP/1\\.[01] 200 ")

    /** Ends a proof request still running at PROOF_DEADLINE_MS: close() from another thread closes its socket, which
     *  ends the connect or read it is blocked in. */
    private val deadlines = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "DaemonAuth deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** True only when our daemon answered a fresh nonce while it held the port (another app may have passed the nonce
     *  on, see the class comment), so a new connection made right after reaches it. Blocking network I/O: never on
     *  the main thread. False on any failure, a daemon still starting included, and within about PROOF_DEADLINE_MS
     *  whatever the port's holder sends. */
    fun daemonProven(): Boolean = try {
        val nonce = hex(ByteArray(32).also { random.nextBytes(it) })
        // Proxy.NO_PROXY: to the port itself, not to a proxy that could relay the nonce (class comment). A socket of
        // its own, never pooled, and closed after this one answer.
        Socket(Proxy.NO_PROXY).use { socket ->
            val deadline = deadlines.schedule(Runnable { socket.close() }, PROOF_DEADLINE_MS, TimeUnit.MILLISECONDS)
            try {
                socket.connect(InetSocketAddress(HOST, ANDROID_DAEMON_PORT), 1000)
                socket.soTimeout = 2000
                // Connection: close, so the daemon closes the socket after its answer and that ends the read below.
                val request = "GET /api/v1/app-proof?nonce=$nonce HTTP/1.1\r\n" +
                    "Host: $HOST:$ANDROID_DAEMON_PORT\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
                // Only the first PROOF_MAX_BYTES of the answer, headers included, and matched as text rather than
                // parsed: nothing the port's holder sends can grow the heap or the stack (see PROOF_DEADLINE_MS).
                val answer = readAtMost(socket.getInputStream(), PROOF_MAX_BYTES)
                val bodyAt = answer.indexOf("\r\n\r\n")
                // A 200 only: a redirect is not followed, the answer must come from this port. The proof is taken from
                // the body, after the blank line that ends the headers.
                STATUS_OK.containsMatchIn(answer) && bodyAt >= 0 && run {
                    val proof = PROOF.find(answer, bodyAt + 4)?.groupValues?.get(1).orEmpty()
                    // The daemon's message (appSecretProof): the address and port it was asked on, then the nonce.
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(DAEMON_APP_SECRET.toByteArray(), "HmacSHA256"))
                    val want = hex(mac.doFinal("turbollm-app-proof\n$HOST:$ANDROID_DAEMON_PORT\n$nonce".toByteArray()))
                    MessageDigest.isEqual(want.toByteArray(), proof.toByteArray()) // constant time
                }
            } finally {
                deadline.cancel(false)
            }
        }
    } catch (_: Throwable) {
        false // an Error too: no answer from the port may crash the app (comment at PROOF_DEADLINE_MS)
    }

    /** A connection to [path] on the daemon carrying the secret, opened only once [daemonProven]. Throws otherwise,
     *  which every caller already handles as an unreachable daemon. Callers must not override its Connection header. */
    fun open(path: String): HttpURLConnection {
        if (!daemonProven()) throw IOException("no proof from port $ANDROID_DAEMON_PORT that it is our daemon; secret withheld")
        // Proxy.NO_PROXY: the secret goes to the port itself, never through a proxy (class comment).
        return (URL("$BASE$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            // Its own new socket, and none left in the pool for the next proof or request to pick up (class comment).
            setRequestProperty("Connection", "close")
            setRequestProperty("X-TurboLLM-App", DAEMON_APP_SECRET)
        }
    }

    /** Up to [max] bytes of [input] as text; whatever follows is never read. */
    private fun readAtMost(input: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val got = input.read(buf, n, max - n)
            if (got < 0) break
            n += got
        }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
