package com.turbollm.app

import android.content.Context
import dev.turbollm.sdk.ServerHandle
import dev.turbollm.sdk.ServerOptions
import dev.turbollm.sdk.TurboLLM
import dev.turbollm.sdk.TurboLLMConfig
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking

/** The app's one SDK core and its local server, which the daemon reaches as its `turbollm-sdk` engine. */
object TurboLLMBootstrap {
    /** Per process, like DAEMON_APP_SECRET (MainActivity.kt). Never logged in a release build. */
    val SDK_TOKEN: String = randomHex(32)

    /** EngineService asks for the same config, so both share the process's one core. */
    fun config(ctx: Context) = TurboLLMConfig(modelRoot = File(ctx.filesDir, "models"), allowLoadByPath = true)

    /** Blocking; call off the main thread. Idempotent: one server for the process lifetime, never rebound to a new
     *  port, because the daemon learned the URL once, through its env. */
    fun ensureServer(ctx: Context): ServerHandle = runBlocking {
        val core = TurboLLM.getOrCreate(ctx.applicationContext, config(ctx))
        core.server.value ?: core.startServer(ServerOptions(port = 0, token = SDK_TOKEN))
    }
}

private fun randomHex(byteCount: Int): String = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
