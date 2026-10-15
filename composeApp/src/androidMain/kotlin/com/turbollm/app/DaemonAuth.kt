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
 *  this process started.
 *
 *  Any app on the phone can listen on 127.0.0.1:ANDROID_DAEMON_PORT while our daemon does not: before it binds at
 *  launch (EngineService polls from the start, and the daemon retries a busy port for about 10 s), or for a moment
 *  while a LAN toggle rebinds it. A secret sent there would let that app use the whole API for as long as this process
 *  lives. So before each request the port's holder must answer a fresh random nonce with HMAC-SHA256 under the secret
 *  (GET /api/v1/app-proof, daemon auth.ts appSecretProof), which only our daemon can do and which is no use as the
 *  secret itself. A proof per request rather than one kept until a request fails: an app that took the port over
 *  between two requests would answer the second one, not fail it. */
object DaemonAuth {
    private const val BASE = "http://127.0.0.1:$ANDROID_DAEMON_PORT"
    private val random = SecureRandom()

    /** True only when whatever holds the port proved it has the secret. Blocking network I/O: never on the main
     *  thread. False on any failure, a daemon still starting included. */
    fun daemonProven(): Boolean = try {
        val nonce = hex(ByteArray(32).also { random.nextBytes(it) })
        val conn = URL("$BASE/api/v1/app-proof?nonce=$nonce").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false // the answer must come from this port, not from wherever it points
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
     *  which every caller already handles as an unreachable daemon. */
    fun open(path: String): HttpURLConnection {
        if (!daemonProven()) throw IOException("no proof from port $ANDROID_DAEMON_PORT that it is our daemon; secret withheld")
        return (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-TurboLLM-App", DAEMON_APP_SECRET)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
