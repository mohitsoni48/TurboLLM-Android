package com.turbollm.app

/** iOS `actual`: intentionally unimplemented. This exists only so the KMP module compiles
 *  for the iOS targets declared in build.gradle.kts — real iOS work (bridging into
 *  `nodejs-mobile`'s iOS runtime via Objective-C/Swift interop, a WKWebView shell) is not
 *  scoped or started. Do not wire this into an actual iOS app target until that happens. */
actual class NodeRuntimeLauncher actual constructor() {
    actual fun start() {
        throw NotImplementedError(
            "iOS embedded-runtime bridging isn't implemented yet — this class only keeps " +
                "the KMP source-set shape ready for it. See BLUEPRINT.md; iOS work hasn't started.",
        )
    }
}
