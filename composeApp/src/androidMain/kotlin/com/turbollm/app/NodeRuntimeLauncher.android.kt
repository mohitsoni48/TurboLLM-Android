package com.turbollm.app

/** Android `actual`: bridges into `nodejs-mobile`'s embedded Node runtime via JNI
 *  (native-lib.cpp). The inline script is a throwaway Spike A payload — proving the
 *  runtime boots and can serve real `process.versions` over HTTP — not the real daemon;
 *  swapping in `turbollm/dist/cli.js` is Phase 3 (BLUEPRINT.md §7), not this spike. */
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

    private external fun startNodeWithArguments(arguments: Array<String>): Int
}
