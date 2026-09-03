package com.turbollm.app

/** Starts the embedded Node.js runtime that hosts the TurboLLM daemon.
 *
 *  Platform-specific by nature, not a KMP convenience split: Android bridges into
 *  `nodejs-mobile` via JNI (see the `androidMain` actual + `cpp/native-lib.cpp`); iOS would
 *  bridge through its own Objective-C/Swift interop. BLUEPRINT.md's plan is Android-only for
 *  now — the `iosMain` actual is a deliberate stub, not a working implementation, so this
 *  expect/actual split exists purely to keep the module shape ready for iOS later without
 *  claiming iOS support that doesn't exist yet. */
expect class NodeRuntimeLauncher() {
    fun start()
}
