package com.turbollm.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
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
 *  A proof vouches only for the listener, so the proof and the request each go out on a new socket (Connection:
 *  close), never on a pooled one. Android's HttpURLConnection keeps keep-alive sockets in one pool for the process and
 *  reuses them even after disconnect(), and a pooled socket stays connected to whoever accepted it: an app holding the
 *  port at launch could let our daemon have it, keep the socket we pooled, pass each nonce on to the daemon, and
 *  receive the request that follows on that socket. With every socket to the port closed after its one request,
 *  nothing to it is ever in the pool. What this guarantees: the secret goes out only on a connection opened just after
 *  our daemon, on another new connection, proved it held the port. It reaches another app only if the daemon gave the
 *  port up in between, i.e. a LAN-toggle rebind; an app that is still on the port when the daemon binds again makes
 *  the daemon exit (cli.ts), so what is left is an app that binds and lets go inside that gap of a few ms, just as a
 *  request is made. */
object DaemonAuth {
    private const val BASE = "http://127.0.0.1:$ANDROID_DAEMON_PORT"
    private val random = SecureRandom()

    /** True only when our daemon answered a fresh nonce while it held the port (another app may have passed the nonce
     *  on, see the class comment), so a new connection made right after reaches it. Blocking network I/O: never on
     *  the main thread. False on any failure, a daemon still starting included. */
    fun daemonProven(): Boolean = try {
        val nonce = hex(ByteArray(32).also { random.nextBytes(it) })
        val conn = URL("$BASE/api/v1/app-proof?nonce=$nonce").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false // the answer must come from this port, not from wherever it points
        conn.setRequestProperty("Connection", "close") // a new socket, not left in the pool for open() (class comment)
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            conn.responseCode == 200 && run {
                val proof = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("proof")
                // The daemon's message (appSecretProof): the address and port it was asked on, then the nonce.
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(DAEMON_APP_SECRET.toByteArray(), "HmacSHA256"))
                val want = hex(mac.doFinal("turbollm-app-proof\n127.0.0.1:$ANDROID_DAEMON_PORT\n$nonce".toByteArray()))
                MessageDigest.isEqual(want.toByteArray(), proof.toByteArray()) // constant time
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    /** A connection to [path] on the daemon carrying the secret, opened only once [daemonProven]. Throws otherwise,
     *  which every caller already handles as an unreachable daemon. Callers must not override its Connection header. */
    fun open(path: String): HttpURLConnection {
        if (!daemonProven()) throw IOException("no proof from port $ANDROID_DAEMON_PORT that it is our daemon; secret withheld")
        return (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            // Its own new socket, and none left in the pool for the next proof or request to pick up (class comment).
            setRequestProperty("Connection", "close")
            setRequestProperty("X-TurboLLM-App", DAEMON_APP_SECRET)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
