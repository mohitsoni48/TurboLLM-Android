package com.turbollm.app

/** Renderer crashes within RENDERER_CRASH_WINDOW_MS after which the WebView is not built again by itself
 *  (RendererCrashes). */
internal const val RENDERER_CRASHES = 3

internal const val RENDERER_CRASH_WINDOW_MS = 60_000L

/** Decides, at each death of the WebView's renderer (DaemonWebView.kt, onRenderProcessGone), whether the screen builds
 *  a fresh WebView. A renderer killed for memory (didCrash false: Android reclaiming it, most often with the app in the
 *  background) is always followed by a fresh WebView, and is not counted: it says nothing about the page. One that
 *  crashed is too, unless it is the RENDERER_CRASHES-th crash within RENDERER_CRASH_WINDOW_MS. A page that crashes its
 *  renderer each time it loads was otherwise built, loaded and crashed again in a loop for as long as the app was on
 *  screen, a new renderer process each round; the screen shows its Reload screen instead. Reload clears nothing: one
 *  more crash inside the window brings that screen back at once. Main thread only. */
internal class RendererCrashes {
    /** When the crashes still inside the window happened (SystemClock.elapsedRealtime), oldest first. */
    private val times = ArrayDeque<Long>()

    /** One renderer death at [now] (ms): true to build a fresh WebView, false to show the Reload screen. */
    fun rebuild(didCrash: Boolean, now: Long): Boolean {
        if (!didCrash) return true
        while (times.isNotEmpty() && now - times.first() >= RENDERER_CRASH_WINDOW_MS) times.removeFirst()
        times.addLast(now)
        return times.size < RENDERER_CRASHES
    }
}
