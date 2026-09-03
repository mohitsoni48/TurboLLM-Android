package com.turbollm.app

/** Android `actual`: bridges into `nodejs-mobile`'s embedded Node runtime via JNI
 *  (native-lib.cpp). `start()` is Spike A's throwaway payload — proving the runtime boots
 *  and can serve real `process.versions` over HTTP, already verified live on a real
 *  emulator. `startScript()` is the real daemon path (BLUEPRINT.md §7 / Spike D): runs an
 *  actual JS file (`turbollm/dist/cli.js`) instead of an inline snippet, with the
 *  environment variables Node needs — an Android app process has no real Unix `$HOME` by
 *  default, and the daemon resolves its own data directory (`~/.turbollm`) via Node's
 *  `os.homedir()`, which reads `process.env.HOME` first. */
actual class NodeRuntimeLauncher actual constructor() {
    init {
        System.loadLibrary("native-lib")
        System.loadLibrary("node")
    }

    actual fun start() {
        startNodeWithArguments(
            arrayOf(
                "node", "-e",
                """console.log('TurboLLM: Node.js embedded runtime booted successfully');
                  |console.log('TurboLLM: Node version: ' + process.version);
                  |console.log('TurboLLM: Platform: ' + process.platform);
                  |console.log('TurboLLM: Arch: ' + process.arch);
                  |var http = require('http');
                  |var server = http.createServer(function(request, response) {
                  |  response.setHeader('Content-Type', 'application/json');
                  |  response.end(JSON.stringify({
                  |    ok: true,
                  |    node: process.version,
                  |    v8: process.versions.v8,
                  |    uv: process.versions.uv,
                  |    platform: process.platform,
                  |    arch: process.arch
                  |  }));
                  |});
                  |server.listen(6996, '127.0.0.1', function() {
                  |  console.log('TurboLLM: HTTP server listening on 127.0.0.1:6996');
                  |});
                  |""".trimMargin(),
            ),
        )
    }

    /** Runs a real JS file (the actual daemon) instead of an inline snippet. `env` is
     *  applied via setenv() before Node starts (see native-lib.cpp) — must include at least
     *  HOME, since the daemon can't resolve its data directory without it.
     *
     *  `preloadScripts` become `--require <path>` flags placed BEFORE `scriptPath` — this is
     *  load-bearing, not cosmetic. Confirmed live: a plain top-level ESM import at the very
     *  top of cli.ts's own source does NOT reliably run before every other module in its
     *  import graph, because Node's ESM linker executes CommonJS dependencies' top-level code
     *  as part of resolving their NAMED EXPORTS during the LINK phase — which covers the
     *  whole graph before ANY module (including the entry module's own body) reaches
     *  evaluation. A `--require` preload runs via Node's own CLI bootstrap strictly before it
     *  even begins loading the entry module, so it's the only mechanism that reliably beats
     *  that phase (see android-preload.cjs in the daemon's dist/, which polyfills the
     *  `File` global undici's webidl layer needs at its own module-load time). */
    fun startScript(scriptPath: String, env: Map<String, String>, args: Array<String> = emptyArray(), preloadScripts: Array<String> = emptyArray()) {
        for ((name, value) in env) nativeSetEnv(name, value)
        val requireFlags = preloadScripts.flatMap { listOf("--require", it) }.toTypedArray()
        startNodeWithArguments(arrayOf("node", *requireFlags, scriptPath, *args))
    }

    private external fun nativeSetEnv(name: String, value: String)
    private external fun startNodeWithArguments(arguments: Array<String>): Int
}
