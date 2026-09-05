package com.turbollm.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.view.WindowInsets
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay

private val DAEMON_URL = "http://127.0.0.1:$ANDROID_DAEMON_PORT/"

/** Confirmed live (2026-09-03) via Chrome DevTools attached over the WebView's own devtools
 *  socket: this WebView (Android System WebView, Chromium 145 on this emulator) resolves ANY
 *  raw `vh`-family CSS length (`vh`/`dvh`/`svh`/`lvh`) to a literal 0px — not just on
 *  html/body/#root (the first occurrence found, fixed below by setting an explicit pixel
 *  height from `window.innerHeight`), but on ANY element anywhere in the app that uses one,
 *  including ones that only exist inside dynamically-rendered content (confirmed on a second,
 *  unrelated occurrence: the Models → Discover results list, `max-h-[45vh]`, which only
 *  renders after an async fetch completes — so a page-load-time-only fix can't reach it. DOM
 *  and CSS both load correctly (zero console errors, real data in `innerText`) but the
 *  affected subtree paints zero pixels regardless.
 *
 *  Fixed generally: htmlFix pins html/body/#root's own height in pixels (unchanged from the
 *  original fix). vhClassFix separately scans element classNames for Tailwind's arbitrary-value
 *  bracket syntax (`h-[45vh]`, `max-h-[100dvh]`, `min-h-[50svh]`, …) — which is a literal
 *  substring of the class attribute, not something requiring stylesheet introspection — and
 *  overrides the corresponding CSS property with an `!important` inline pixel value computed
 *  from `window.innerHeight`. A MutationObserver re-runs it on every newly-added subtree so
 *  content rendered after page load (any async-fetched list, not just Discover) is covered
 *  too, not just what exists at `onPageFinished` time.
 *
 *  QA_BUGS.md BUG-05 (2026-09-04): the same bug also breaks Tailwind's NAMED vh-family
 *  utilities (`min-h-dvh`, `h-screen`, …), not just the bracket arbitrary-value form above —
 *  `.app-shell`'s `min-h-dvh` (Shell.tsx, document-scroll mode) collapsing to 0 left the
 *  bottom tab bar rendering right after short content instead of pinned to the real bottom of
 *  the screen. fixNamedVhClasses covers this with one small stylesheet (updated on resize)
 *  instead of per-element scanning — a CSS class selector already applies to elements added
 *  after it exists, so no MutationObserver entry is needed for this half of the fix. */
private const val HEIGHT_FIX_JS = """
(function() {
  function htmlFix() {
    var px = window.innerHeight + 'px';
    document.documentElement.style.height = px;
    document.body.style.height = px;
    var root = document.getElementById('root');
    if (root) root.style.height = px;
  }

  var VH_CLASS_RE = /^(min-h|max-h|h)-\[(\d+(?:\.\d+)?)(?:d|s|l)?vh\]${'$'}/;
  var PROP_BY_PREFIX = { 'h': 'height', 'min-h': 'min-height', 'max-h': 'max-height' };

  function fixVhElement(el) {
    if (!el.classList) return;
    for (var i = 0; i < el.classList.length; i++) {
      var m = VH_CLASS_RE.exec(el.classList[i]);
      if (!m) continue;
      var prop = PROP_BY_PREFIX[m[1]];
      var px = (parseFloat(m[2]) / 100 * window.innerHeight) + 'px';
      el.style.setProperty(prop, px, 'important');
    }
  }

  function fixVhSubtree(node) {
    if (node.nodeType !== 1) return;
    fixVhElement(node);
    var descendants = node.querySelectorAll ? node.querySelectorAll('[class*="vh]"]') : [];
    for (var i = 0; i < descendants.length; i++) fixVhElement(descendants[i]);
  }

  // QA_BUGS.md BUG-05: the same 0px-vh bug also breaks Tailwind's NAMED viewport-height
  // utilities (`min-h-dvh`, `h-dvh`, `h-screen`, ...) — not just the bracket arbitrary-value
  // form vhClassFix (above) already covers. `.app-shell` uses `min-h-dvh` in document-scroll
  // mode (Shell.tsx) to let MobileNav stick to the true viewport bottom; with min-height
  // collapsing to 0 the shell only grew to its own (often short) content, so the tab bar
  // rendered right after that content instead of pinned to the screen — reported as "the
  // tab bar floats mid-screen with a dead white gap below it" on tabs with little content.
  // A single stylesheet, updated on resize, covers every element with these classes at
  // once — no MutationObserver needed here, unlike vhClassFix's inline-style scan, since
  // CSS class selectors apply to future elements automatically.
  var namedVhStyleEl = null;
  function fixNamedVhClasses() {
    var px = window.innerHeight + 'px';
    if (!namedVhStyleEl) {
      namedVhStyleEl = document.createElement('style');
      document.documentElement.appendChild(namedVhStyleEl);
    }
    namedVhStyleEl.textContent =
      '.h-dvh,.h-svh,.h-lvh,.h-screen{height:' + px + ' !important}' +
      '.min-h-dvh,.min-h-svh,.min-h-lvh,.min-h-screen{min-height:' + px + ' !important}' +
      '.max-h-dvh,.max-h-svh,.max-h-lvh,.max-h-screen{max-height:' + px + ' !important}';
  }

  function fixAll() {
    htmlFix();
    fixVhSubtree(document.documentElement);
    fixNamedVhClasses();
  }

  fixAll();
  window.addEventListener('resize', fixAll);

  var observer = new MutationObserver(function(mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var added = mutations[i].addedNodes;
      for (var j = 0; j < added.length; j++) fixVhSubtree(added[j]);
    }
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();
"""

/** Injects the native window insets as CSS custom properties so the web UI's
 *  `var(--tllm-safe-top)` / `var(--tllm-safe-bottom)` resolve to real pixel values.
 *  Android's System WebView resolves `env(safe-area-inset-*)` to 0px on many builds,
 *  so we read the insets natively and patch them in via JS (same pattern as HEIGHT_FIX_JS).
 *  Called after HEIGHT_FIX_JS so the CSS vars are ready before the web layout reads them.
 *
 *  [topPx]/[bottomPx] are PHYSICAL pixels (that's what WindowInsets reports) — CSS `px`
 *  inside the WebView is device-independent and gets multiplied by devicePixelRatio at
 *  render time, so injecting the raw physical value as a literal "96px" over-applies it by
 *  that same factor (3x on this dpr=3 device: a real ~96px status bar rendered as a ~288px
 *  gap, visible as an oversized black band above and below the actual content — QA-reported
 *  live on a real device). Divide by the WebView's own devicePixelRatio inside the injected
 *  JS itself, not by Android's `density`, so this can't drift from whatever DPR the page is
 *  actually rendering at. */
private fun WebView.injectSafeAreaInsets(topPx: Int, bottomPx: Int) {
    val js = """
(function() {
  var s = document.documentElement.style;
  var dpr = window.devicePixelRatio || 1;
  s.setProperty('--tllm-safe-top', (${topPx} / dpr) + 'px');
  s.setProperty('--tllm-safe-bottom', (${bottomPx} / dpr) + 'px');
})();
""".trimIndent()
    evaluateJavascript(js, null)
}

/** Read the current window insets in a backward-compatible way (API 30+ WindowInsets API,
 *  falling back to deprecated getWindowVisibleDisplayFrame on older APIs). Returns pair of
 *  (topInsetPx, bottomInsetPx) in pixels. */
private fun readWindowInsets(windowManager: WindowManager): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val r = windowManager.currentWindowMetrics
        val insets = r.windowInsets
        val top = insets.getInsets(WindowInsets.Type.statusBars()).top
        val bottom = if (insets.isVisible(WindowInsets.Type.navigationBars()))
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else 0
        top to bottom
    } else @Suppress("DEPRECATION")
    {
        val r = windowManager.currentWindowMetrics
        val insets = r.windowInsets
        val top = insets.getInsets(WindowInsets.Type.statusBars()).top
        val bottom = if (insets.isVisible(WindowInsets.Type.navigationBars()))
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else 0
        top to bottom
    }
}

/** Android's real product UI (BLUEPRINT.md §6): the daemon already serves its own full web UI
 *  at DAEMON_URL, identical to the desktop/LAN case — this just hosts it in a full-screen
 *  WebView once the daemon is actually listening, instead of reimplementing any of that UI
 *  natively. Replaces Spike A's `App()` screen as MainActivity's real content. */
@Composable
fun DaemonWebViewScreen() {
    var ready by remember { mutableStateOf(false) }
    val client = remember { DaemonClient() }

    LaunchedEffect(Unit) {
        while (!ready) {
            ready = client.isDaemonReachable(ANDROID_DAEMON_PORT)
            if (!ready) delay(500)
        }
    }

    TurboLLMTheme {
        // The splash pins to a fixed dark background rather than following
        // MaterialTheme.colorScheme.background: that follows ANDROID's system day/night
        // setting, which is a separate, independent preference from the web UI's own
        // dark/light choice (its own in-app store, read only once the WebView itself has
        // loaded). Founder-reported: on a phone with system light mode but the app's own
        // theme set to dark, the splash flashed a light background for a moment before the
        // real (dark) app content appeared underneath it — jarring, and exactly the kind of
        // mismatch QA_BUGS.md BUG-08 already fixed once for the even-earlier pre-Compose
        // window background. The loaded WebView content still follows its own real theme
        // once `ready` — this only fixes the splash's own brief window.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (!ready) Color(0xFF0E0E10) else MaterialTheme.colorScheme.background,
        ) {
            if (!ready) {
                // Real splash screen (BLUEPRINT.md §6 Phase 2, themes.xml's own note — this was
                // explicitly deferred past Spike A). QA_BUGS.md BUG-09 is why this is Lottie and
                // not Compose Multiplatform's CircularProgressIndicator: that one sometimes
                // renders as a static curved stroke instead of animating, because the animation
                // clock doesn't reliably fire in the KMP runtime during the long daemon-boot
                // window. Lottie runs on its own Choreographer-driven clock, same reasoning as
                // the native ProgressBar this replaces, but branded instead of a generic spinner.
                // The badge asset (raw/splash_bolt.json) embeds the real launcher icon mark —
                // orange circle + bolt, not just the bare white glyph — as a base64 image layer
                // (see ic_launcher_background.xml for its provenance), so it reads as branded and
                // in-color no matter what's behind it. Pop-in entrance, then a continuous gentle
                // scale pulse plus a slow rotation wobble — still simple keyframes on one image
                // layer, no vector tracing needed.
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.splash_bolt))
                val progress by animateLottieCompositionAsState(
                    composition,
                    iterations = LottieConstants.IterateForever,
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress = { progress },
                        modifier = Modifier.size(200.dp),
                    )
                    // Explicit light color, not the theme's default text color: the Surface
                    // above is pinned dark regardless of system day/night (see its own comment),
                    // so text following the theme's light-mode color would be near-invisible.
                    Text(
                        "Starting TurboLLM…",
                        color = Color(0xFFEDEDEC),
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            } else {
                TurboLLMWebView()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TurboLLMWebView() {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // QA_BUGS.md BUG-02: `webViewRef?.canGoBack() == true` as BackHandler's `enabled` directly
    // was a snapshot taken once (whenever `webViewRef` itself was last reassigned, i.e. almost
    // never after the initial composition) — Compose has no way to know `canGoBack()`'s return
    // value changed later, since it's a plain method call on a View, not an observed State. A
    // real page navigation happened to still exit correctly in the earlier top/bottom-inset
    // fixes because those never depended on `canGoBack()` flipping true; the web app's own
    // `history.pushState()` (use-backable-overlay.ts, opening the conversations drawer) does
    // depend on it, and needed a real reactive value. `doUpdateVisitedHistory` fires for both
    // ordinary page loads AND same-document pushState/popstate navigation, so it is the one
    // WebViewClient hook that can keep this correct for both cases.
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(true)
            // QA_BUGS.md BUG-13: by default the Android System WebView renders as a single
            // opaque NAF="true" node in the accessibility tree — invisible to TalkBack and
            // to UI-Automator/Maestro element selectors. Chrome's accessibility bridge for
            // WebView must be explicitly enabled; setWebContentsDebuggingEnabled alone is
            // not enough. Flagging the view as important for accessibility helps the
            // platform expose at least the WebView container itself to TalkBack.
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(HEIGHT_FIX_JS, null)
                        // QA_BUGS.md BUG-03/04/06/07: inject native insets as CSS custom props
                        // so the web UI's safe-area padding actually applies on Android.
                        val ins = readWindowInsets(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        view.injectSafeAreaInsets(ins.first, ins.second)
                        canGoBack = view.canGoBack()
                    }
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        canGoBack = view.canGoBack()
                    }
                }
                loadUrl(DAEMON_URL)
                webViewRef = this
            }
        },
    )
}
