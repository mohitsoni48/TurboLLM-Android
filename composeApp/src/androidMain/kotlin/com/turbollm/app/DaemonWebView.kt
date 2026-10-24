package com.turbollm.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val TAG = "TurboLLM"

private val DAEMON_URL = "http://127.0.0.1:$ANDROID_DAEMON_PORT/"

/** The daemon UI's origin, with the trailing slash so a look-alike host cannot match. */
private val DAEMON_ORIGIN = DAEMON_URL

/** Schemes the WebView loads itself: web pages, and what the UI's own frames use. Navigations to any other scheme are
 *  never loaded (shouldOverrideUrlLoading); turbollm-app is handled before that check. */
private val WEBVIEW_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

/** Contact links handed to the app that handles them (tel only opens the dialer). The web UI's markdown and model-card
 *  sanitizers allow mailto, xmpp, irc and ircs links, and GFM turns a bare e-mail address in a reply into a mailto
 *  link. */
private val HANDED_OFF_SCHEMES = setOf("mailto", "xmpp", "irc", "ircs", "tel")

/** A link handed to another app goes out as a browser sends one: ACTION_VIEW with CATEGORY_BROWSABLE, so only an
 *  activity that declares it, i.e. accepts links from web pages, can be started. A bare ACTION_VIEW also matched
 *  exported activities that never declared it: ones their apps open to other apps' code, not to any link on a page. */
private fun linkIntent(url: Uri) = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)

/** The page is laid out at the WebView's own height because the factory gives the WebView MATCH_PARENT layout
 *  params (TurboLLMWebView). It had none, so AndroidView's holder added it with ViewGroup's default, WRAP_CONTENT, and
 *  Chromium lays out the page of a WebView whose layout params are WRAP_CONTENT high at height 0 (android_webview's
 *  AwLayoutSizer turns Blink's forceZeroLayoutHeight on for it), while the view itself was measured full size and
 *  window.innerHeight was right. Every vh, dvh, svh, lvh and vmin length, and every percentage height resolved against
 *  the viewport (html, body and #root at 100%), was 0px. That is the "0px vh" first seen live over DevTools on
 *  2026-09-03 and put down to the System WebView. It was worked around one class shape at a time (html/body/#root, the
 *  Discover list's `max-h-[45vh]`, QA_BUGS.md BUG-05's `min-h-dvh`) until the model picker's
 *  `max-h-[min(60vh,…)]` (TurboLLM #270), a shape the workaround did not cover, opened as an empty 280x10 strip.
 *
 *  HEIGHT_FIX_JS stays as a fallback for a WebView that still lays the page out at height 0, which no device check has
 *  ruled out yet. It measures a `height:100vh` element once per page (at the first resize that gives the window a
 *  height, on a page that finished loading before it had one) and does nothing unless that is under 1px while
 *  window.innerHeight is not. Then: htmlFix pins html, body and #root to window.innerHeight; every `h-[…]`,
 *  `min-h-[…]` and `max-h-[…]` class (Tailwind's `!` included) whose value has a viewport unit, alone or anywhere
 *  inside min()/max()/calc()/clamp(), gets that value as an `!important` inline style of the same property, each such
 *  unit computed in px and every other term (var() ones included) left as it is, on elements added or given the class
 *  later too (a MutationObserver). The value is the one in the class's rule, as Tailwind wrote it (it spaces out a
 *  calc()'s operators: `h-[calc(100vh-2rem)]` is `calc(100vh - 2rem)`), or the class name's, `_` read as a space,
 *  when no rule is found. Tailwind's named utilities (`h-dvh`, `min-h-screen`, …) get one stylesheet. All of it
 *  follows resize. It answers whether it did this, and onPageFinished logs a warning when it did, so logcat shows a
 *  WebView still laying pages out at height 0. scripts/webview-height-fix.test.mjs runs this string in Chromium under
 *  both kinds of host. */
private const val HEIGHT_FIX_JS = """
(function() {
  // onPageFinished can come more than once for the same page: the first run decided (or waits to), and its listeners
  // are in place.
  if (window.__turbollmHeightFix !== undefined) return null;

  // Only a WebView that lays the page out at height 0 measures 100vh as nothing while the window has a height.
  function zeroVh() {
    var probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;top:0;left:0;width:0;height:100vh;visibility:hidden';
    document.documentElement.appendChild(probe);
    var zero = probe.getBoundingClientRect().height < 1;
    document.documentElement.removeChild(probe);
    return zero;
  }

  function htmlFix() {
    var px = window.innerHeight + 'px';
    document.documentElement.style.height = px;
    document.body.style.height = px;
    var root = document.getElementById('root');
    if (root) root.style.height = px;
  }

  // Tailwind's arbitrary-value height classes, whatever the value, with or without its `!` (important) before or after:
  // `max-h-[60vh]`, `h-[calc(100vh-2rem)]`, `h-[50vh]!`, the model picker's
  // `max-h-[min(60vh,var(--radix-dropdown-menu-content-available-height))]`.
  var VH_CLASS_RE = /^!?(min-h|max-h|h)-\[(.+)\]!?${'$'}/;
  var PROP_BY_PREFIX = { 'h': 'height', 'min-h': 'min-height', 'max-h': 'max-height' };
  // A number with a unit that is 0px or wrong here (vh and vb, which are 0, vmin, 0 as well, and vmax, the width: each
  // also d/s/l-prefixed), as a term of its own: after the start, a space, a comma, a bracket or an operator, so the
  // digits inside a custom property's name (var(--a-10vh)) are not one.
  var VP_UNIT_RE = /(^|[\s,(*\/+])(-?(?:\d+\.?\d*|\.\d+))(?:d|s|l)?(vh|vb|vmin|vmax)(?![\w-])/g;

  // The value of [prop] in the rule for class [cls], as Tailwind wrote it, or '' when there is none. Tailwind does not
  // only read '_' as a space: it spaces out a calc()'s operators too (`h-[calc(100vh-2rem)]` is `calc(100vh - 2rem)`),
  // so the class name alone is not always the CSS. Rules at the top level and in @layer blocks, where Tailwind puts its
  // utilities, are collected again when a stylesheet comes or goes (a screen's own CSS loaded later).
  var rules = null, ruleSheets = -1;
  function declared(cls, prop) {
    var sheets = document.styleSheets;
    if (ruleSheets !== sheets.length) {
      rules = {};
      ruleSheets = sheets.length;
      var collect = function(list) {
        for (var i = 0; i < list.length; i++) {
          var rule = list[i];
          if (rule.selectorText !== undefined) rules[rule.selectorText] = rule.style;
          else if (window.CSSLayerBlockRule && rule instanceof CSSLayerBlockRule) collect(rule.cssRules);
        }
      };
      for (var s = 0; s < sheets.length; s++) {
        try { collect(sheets[s].cssRules); } catch (e) {} // another origin's stylesheet cannot be read
      }
    }
    var style = rules['.' + CSS.escape(cls)];
    return style ? style.getPropertyValue(prop) : '';
  }

  // A value as a class name spells it, for a class no rule was found for: '_' is a space, '\_' an underscore, and a
  // custom property's name keeps its underscores.
  function decoded(raw) {
    return raw.replace(/\\_|--[\w-]+|_/g, function(t) { return t === '_' ? ' ' : t === '\\_' ? '_' : t; });
  }

  // [value] with each viewport-unit term in px, or null when it has none.
  function pxValue(value) {
    var h = window.innerHeight, w = window.innerWidth;
    var px = value.replace(VP_UNIT_RE, function(term, before, n, unit) {
      var base = unit === 'vmin' ? Math.min(w, h) : unit === 'vmax' ? Math.max(w, h) : h;
      return before + (parseFloat(n) / 100 * base) + 'px';
    });
    return px === value ? null : px;
  }

  function fixVhElement(el) {
    if (!el.classList) return;
    var next = {}, any = false;
    for (var i = 0; i < el.classList.length; i++) {
      var cls = el.classList[i], m = VH_CLASS_RE.exec(cls);
      if (!m) continue;
      var prop = PROP_BY_PREFIX[m[1]];
      var px = pxValue(declared(cls, prop) || decoded(m[2]));
      if (px) { next[prop] = px; any = true; }
    }
    var prev = el.__turbollmVh;
    if (!prev && !any) return;
    // What was set for a class the element no longer has goes too, so the class's height leaves with it.
    for (var p in prev) if (!(p in next)) el.style.removeProperty(p);
    for (var q in next) el.style.setProperty(q, next[q], 'important');
    el.__turbollmVh = any ? next : undefined;
  }

  function fixVhSubtree(node) {
    if (node.nodeType !== 1) return;
    fixVhElement(node);
    var descendants = node.querySelectorAll ? node.querySelectorAll('[class*="h-["]') : [];
    for (var i = 0; i < descendants.length; i++) fixVhElement(descendants[i]);
  }

  // QA_BUGS.md BUG-05: Tailwind's NAMED viewport-height utilities (`min-h-dvh`, `h-dvh`, `h-screen`, ...) are 0px
  // too. `.app-shell` uses `min-h-dvh` in document-scroll mode (Shell.tsx) to let MobileNav stick to the true viewport
  // bottom; with min-height collapsing to 0 the shell only grew to its own (often short) content, so the tab bar
  // rendered right after that content instead of pinned to the screen — reported as "the tab bar floats mid-screen
  // with a dead white gap below it" on tabs with little content. A single stylesheet, updated on resize, covers every
  // element with these classes at once — no MutationObserver needed here, unlike the class scan above, since CSS
  // class selectors apply to future elements automatically.
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

  function activate() {
    fixAll();
    window.addEventListener('resize', fixAll);

    // Content rendered after this (an async-fetched list, a Radix portal) and a class set on an existing element later.
    var observer = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        if (mutations[i].type === 'attributes') { fixVhElement(mutations[i].target); continue; }
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) fixVhSubtree(added[j]);
      }
    });
    observer.observe(document.documentElement, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['class'],
    });
  }

  // A page that finished loading before the WebView was laid out has no height yet, so 100vh is 0 whatever the WebView:
  // it decides at the first resize that gives the window one. onPageFinished has had its answer by then, so that
  // warning goes to the page's console.
  if (!(window.innerHeight > 0)) {
    window.__turbollmHeightFix = null;
    window.addEventListener('resize', function sized() {
      if (!(window.innerHeight > 0)) return;
      window.removeEventListener('resize', sized);
      window.__turbollmHeightFix = zeroVh();
      if (!window.__turbollmHeightFix) return;
      activate();
      console.warn("100vh is 0px in this WebView (the page is laid out at height 0); HEIGHT_FIX_JS pins the UI's " +
        'viewport heights to window.innerHeight');
    });
    return null;
  }
  // One probe per page: on a WebView that lays the page out at its own height, that is all the script does.
  window.__turbollmHeightFix = zeroVh();
  if (!window.__turbollmHeightFix) return false;
  activate();
  return true;
})();
"""

/** Sets the web UI's safe-area padding, the `--tllm-safe-top` / `--tllm-safe-bottom` CSS custom properties, on the
 *  page's root element, where it wins over index.css (which, below 768px wide, defaults them to
 *  `env(safe-area-inset-*, 24px)` for a page drawn under the system bars). onPageFinished passes 0: the WebView is
 *  already inset away from every system bar and the display cutout natively (TurboLLMWebView), so the page must not
 *  pad for them again, whatever env() reports inside it.
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

/** Android's real product UI (BLUEPRINT.md §6): the daemon already serves its own full web UI
 *  at DAEMON_URL, identical to the desktop/LAN case — this just hosts it in a full-screen
 *  WebView once the daemon is actually listening, instead of reimplementing any of that UI
 *  natively. Replaces Spike A's `App()` screen as MainActivity's real content. */
@Composable
fun DaemonWebViewScreen() {
    var ready by remember { mutableStateOf(false) }
    // Counts the WebView renderers that have died (TurboLLMWebView's onRenderProcessGone). Each death gives the screen
    // a new key, so the whole TurboLLMWebView is built again: the dead WebView leaves the composition (its onRelease
    // destroys it) and a fresh one is made with the same setup as the first.
    var renderer by remember { mutableIntStateOf(0) }
    // Unless the renderer keeps crashing: a page that crashes it each time it loads was built and crashed again in a
    // loop. After RENDERER_CRASHES crashes within a minute (RendererCrashes; a kill for memory does not count) the
    // screen shows RendererCrashScreen in its place, until the user taps Reload.
    val crashes = remember { RendererCrashes() }
    var crashLoop by remember { mutableStateOf(false) }

    // Up AND ours: the WebView below gets the app-secret cookie the moment it is created, so it waits until the
    // daemon has proved it holds the port (DaemonAuth), not merely until something answers there.
    LaunchedEffect(Unit) {
        while (!ready) {
            ready = withContext(Dispatchers.IO) { DaemonAuth.daemonProven() }
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
            // Always the app's dark color: once the WebView is up, this Surface is only visible as the strips behind
            // the status/navigation bars (the WebView is inset away from them, see TurboLLMWebView), and those carry
            // light icons (MainActivity.enableEdgeToEdge) — a system-light-mode background there would hide them.
            color = Color(0xFF0E0E10),
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
            } else if (crashLoop) {
                RendererCrashScreen(onReload = {
                    Log.i(TAG, "Reload tapped after the WebView renderer kept crashing; loading the UI again")
                    crashLoop = false
                })
            } else {
                key(renderer) {
                    TurboLLMWebView(onRendererGone = { didCrash ->
                        val again = crashes.rebuild(didCrash, SystemClock.elapsedRealtime())
                        if (again) renderer++ else crashLoop = true
                        again
                    })
                }
            }
        }
    }
}

/** Shown in place of the WebView once its renderer has crashed RENDERER_CRASHES times within a minute: plain native
 *  text, and a Reload button that builds the WebView again. The daemon, the loaded model and any download keep
 *  running behind it; only the page is gone. On the splash's pinned dark Surface, hence the explicit light text color
 *  (see the splash's own Text). */
@Composable
private fun RendererCrashScreen(onReload: () -> Unit) {
    // Back leaves the app as from the UI's root screen (TurboLLMWebView's BackHandler), finishing the activity, so it
    // goes through the same close (EngineService.activityFinished) whichever screen is up.
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = true) { activity?.finish() }
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The page keeps crashing.", color = Color(0xFFEDEDEC))
        Button(onClick = onReload, modifier = Modifier.padding(top = 20.dp)) {
            Text("Reload")
        }
    }
}

/** [onRendererGone] is told of each renderer death, and whether it was a crash; it answers whether a fresh WebView
 *  is built (DaemonWebViewScreen). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TurboLLMWebView(onRendererGone: (didCrash: Boolean) -> Boolean) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Hardware back (QA_BUGS.md BUG-02, then QA_UX_REPORT.md F-01). Two earlier shapes of this
    // both failed: `enabled = webViewRef?.canGoBack() == true` was a snapshot Compose could never
    // know had changed, and mirroring `canGoBack()` into Compose state from onPageFinished /
    // doUpdateVisitedHistory still exited to the launcher after an ordinary in-app route change —
    // measured with `history.length == 2` and the WebView plainly able to go back. Rather than
    // keep guessing which WebViewClient hook fires for which navigation kind, ask the WebView
    // itself at the instant back is pressed: that answer cannot be stale, because nothing caches
    // it. The handler therefore stays permanently enabled and reproduces the platform default —
    // finish the Activity — only when there is genuinely nowhere left to go.
    val activity = LocalContext.current as? Activity

    BackHandler(enabled = true) {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) webView.goBack() else activity?.finish()
    }

    // Chat's "attach" control is a plain <input type="file">. A WebView ignores those entirely
    // unless the app supplies a WebChromeClient that opens a picker and hands the chosen URIs
    // back, so on Android the button looked simply dead — tapping it did nothing at all, with no
    // error anywhere. `pendingFiles` bridges the two halves: onShowFileChooser parks the WebView's
    // callback here and launches the intent the page itself asked for (accept=/multiple= are
    // already encoded in FileChooserParams), and the activity result resolves it. The callback
    // MUST be answered exactly once — dropping it leaves the input wedged, unable to open a
    // picker ever again — so cancelling (a null result) is reported too.
    val pendingFiles = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles.value?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        pendingFiles.value = null
    }

    AndroidView(
        // The WebView gets exactly the area nothing draws over: system bars, display cutout and the keyboard.
        // targetSdk 36 makes the window edge-to-edge on Android 15+ whatever the theme says, and the web-side
        // CSS-variable padding this replaces measured that wrong — header under the status bar, tab bar squeezed
        // under the gesture/nav bar. Padding natively is exact on every version and follows the keyboard too
        // (adjustResize no longer resizes an edge-to-edge window), so the web UI needs no insets of its own.
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { context ->
            // Debuggable builds only. DevTools attached over adb runs script in the daemon's origin, where every
            // request carries the HttpOnly app-secret cookie, and can read that cookie too: the whole keyless API,
            // creating an API key included, which a release build must not give an adb host (BUILDING-ENGINES.md).
            // Other apps were never let in (the devtools socket admits only the shell, root and this app). QA over
            // CDP (QA_BUGS.md) uses a debug build; -PturbollmApplicationIdSuffix installs one next to the Play app.
            WebView.setWebContentsDebuggingEnabled(
                (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            )
            // QA_BUGS.md BUG-13: by default the Android System WebView renders as a single
            // opaque NAF="true" node in the accessibility tree — invisible to TalkBack and
            // to UI-Automator/Maestro element selectors. Chrome's accessibility bridge for
            // WebView must be explicitly enabled; setWebContentsDebuggingEnabled alone is
            // not enough. Flagging the view as important for accessibility helps the
            // platform expose at least the WebView container itself to TalkBack.
            WebView(context).apply {
                // MATCH_PARENT both ways, set before AndroidView adds the WebView (it does so right after this
                // factory returns, here and for the WebView built again after a renderer crash). With no layout params
                // it got ViewGroup's default WRAP_CONTENT, and Chromium lays out the page of a WebView whose height is
                // WRAP_CONTENT at height 0: every vh unit and viewport-relative height in the UI was 0px (see
                // HEIGHT_FIX_JS). The view's own size does not change: AndroidView measures it EXACTLY at the size
                // its modifier gives either way.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    // Native actions the web UI asks for by navigating to turbollm-app://<action> (web/src/lib/
                    // android-app.ts). Main frame only: a chat artifact runs in a sandboxed iframe and must not be
                    // able to open the file picker.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        val onDaemonPage = view.url?.startsWith(DAEMON_ORIGIN) == true
                        if (url.scheme == "turbollm-app") {
                            // Honoured only from the daemon's own page, in the main frame.
                            if (request.isForMainFrame && onDaemonPage && url.host == "import-model") {
                                (view.context as? MainActivity)?.startModelImport()
                            }
                            return true // never let the WebView try to load this scheme
                        }
                        // The daemon under the name localhost (a link in a reply or a docs snippet): opened as is, it is a
                        // host the app-secret cookie (scoped to 127.0.0.1) never reaches, so the page asked for an API key.
                        // Load the same page from 127.0.0.1 instead; it stays in the app, like any other daemon link. An
                        // https link to the daemon's port, under either name, is loaded the same way, over http: the
                        // daemon speaks plain http, so loading it as is put a TLS error page (ERR_SSL_PROTOCOL_ERROR)
                        // over the UI.
                        val onDaemonPort = (url.host == "localhost" || url.host == "127.0.0.1") &&
                            url.port == ANDROID_DAEMON_PORT
                        if (request.isForMainFrame && onDaemonPort &&
                            (url.scheme == "https" || (url.scheme == "http" && url.host == "localhost"))
                        ) {
                            val http = url.buildUpon().scheme("http").encodedAuthority("127.0.0.1:$ANDROID_DAEMON_PORT")
                            view.loadUrl(http.build().toString())
                            return true
                        }
                        // A link off the daemon (Hugging Face, docs, a model card) opens outside the app rather than
                        // replacing the app's UI inside its own WebView, where Back would be the only way out. So does
                        // localhost on any port but the daemon's: that is some other app's server, not ours. It goes
                        // where a browser would send it (linkIntent): the browser, or an app that has claimed that web
                        // address (an App Link), but never an activity that does not take links from web pages.
                        val external = (url.scheme == "http" || url.scheme == "https") && url.host != "127.0.0.1" &&
                            !(url.host == "localhost" && url.port == ANDROID_DAEMON_PORT)
                        if (request.isForMainFrame && external) {
                            runCatching { view.context.startActivity(linkIntent(url)) }
                            return true
                        }
                        // A scheme the WebView cannot load: navigated to, it replaced the app's UI with an
                        // ERR_UNKNOWN_URL_SCHEME page, and Back then reloaded the UI from scratch, losing its state. A
                        // contact link (mailto: and the like) opens in the app that handles it, sent the same way and
                        // main frame only, like the links above. Anything else (intent:, market:, file:, a deep link in
                        // another app's own scheme) does nothing, since what the page shows (a reply, a model card) is
                        // not to be trusted to open apps. An https deep link is a web link like any other: it still
                        // opens the app that claimed its address, as it would from a browser.
                        if (url.scheme !in WEBVIEW_SCHEMES) {
                            if (request.isForMainFrame && url.scheme in HANDED_OFF_SCHEMES) {
                                runCatching { view.context.startActivity(linkIntent(url)) }
                            }
                            return true
                        }
                        // Another 127.0.0.1 port would be sent the app-secret cookie (see shouldInterceptRequest
                        // below). Unlike that callback, this one runs again for each redirect hop of a main-frame
                        // navigation, so a link that 302s to 127.0.0.1:<other port> is stopped here, before that
                        // request (and the cookie) goes out.
                        if (url.host == "127.0.0.1" && url.port != ANDROID_DAEMON_PORT) return true
                        return false
                    }

                    // The app-secret cookie (below) is scoped to 127.0.0.1, and cookies ignore ports: anything on the
                    // page pointing at another loopback port (an image in a model card or a reply, a link) would hand
                    // it to whichever app listens there. Nothing the UI needs is on another port, so a request that
                    // starts there is refused here. This only sees a request's FIRST URL, not its redirect hops: an
                    // https image that 302s to 127.0.0.1:<other port> gets past it. Those hops are covered elsewhere:
                    // for images and media by the daemon's CSP on the UI's HTML (APP_SECRET_CSP, daemon server.ts),
                    // which is checked on every redirect; for main-frame navigations by shouldOverrideUrlLoading above.
                    // Runs on a WebView thread, so it touches nothing but the request.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.host != "127.0.0.1" || url.port == ANDROID_DAEMON_PORT) return null
                        return WebResourceResponse(
                            "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                        )
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(HEIGHT_FIX_JS) { activated ->
                            // "true" only from the page's first run, and only when it found 100vh at 0px: the WebView
                            // still lays the page out at height 0 despite its MATCH_PARENT layout params. A page that
                            // finished loading before the WebView had a height answers null, decides at its first
                            // resize, and warns in its own console then.
                            if (activated == "true") {
                                Log.w(
                                    TAG,
                                    "100vh is 0px in this WebView (the page is laid out at height 0); HEIGHT_FIX_JS " +
                                        "pins the UI's viewport heights to window.innerHeight",
                                )
                            }
                        }
                        // The WebView is already inset away from every system bar (the modifier above), so the
                        // web UI's own safe-area padding (Shell.tsx --tllm-safe-top/bottom) must be zero —
                        // anything else would pad twice.
                        view.injectSafeAreaInsets(0, 0)
                    }

                    // The renderer process is gone: Android killed it for memory (most likely with the app in the
                    // background) or it crashed. Without this override Android's default then kills the app's own
                    // process as well, and that process holds the daemon, the loaded model and any download. Handled
                    // instead: this WebView can never be used again, so the screen drops it and builds a fresh one
                    // (DaemonWebViewScreen) with this same factory, set up as on first load: the app-secret cookie
                    // (below), then the UI. After RENDERER_CRASHES crashes within a minute it shows its Reload screen
                    // instead (RendererCrashes). Called on Android 8+ only, where the callback exists.
                    @RequiresApi(Build.VERSION_CODES.O)
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (webViewRef === view) webViewRef = null
                        val next = if (onRendererGone(detail.didCrash())) {
                            "loading the UI again"
                        } else {
                            "it crashed $RENDERER_CRASHES times within ${RENDERER_CRASH_WINDOW_MS / 1000} s, " +
                                "so the UI is not loaded again until Reload"
                        }
                        Log.w(
                            TAG,
                            "WebView renderer gone (didCrash=${detail.didCrash()}, " +
                                "rendererPriorityAtExit=${detail.rendererPriorityAtExit()}); $next",
                        )
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams,
                    ): Boolean {
                        // A picker already waiting means the page asked twice; release the older
                        // callback rather than stranding it.
                        pendingFiles.value?.onReceiveValue(null)
                        pendingFiles.value = filePathCallback
                        return try {
                            filePicker.launch(fileChooserParams.createIntent())
                            true
                        } catch (e: ActivityNotFoundException) {
                            // No picker on the device: answering false lets the WebView fall back
                            // to its own handling instead of leaving the input dead.
                            pendingFiles.value = null
                            false
                        }
                    }
                }
                // The daemon trusts only loopback requests that carry the app secret (DAEMON_APP_SECRET); this cookie is
                // how every request the page makes carries it, WebSocket handshakes included. HttpOnly: page scripts
                // (a chat artifact included) cannot read it. Set before the first load, every launch, since the secret
                // changes with each process, and only once the daemon has proved it holds the port (DaemonWebViewScreen
                // creates this WebView after DaemonAuth.daemonProven).
                CookieManager.getInstance().apply {
                    setCookie(DAEMON_URL, "turbollm_app=$DAEMON_APP_SECRET; Path=/; HttpOnly; SameSite=Strict")
                    flush()
                }
                loadUrl(DAEMON_URL)
                webViewRef = this
            }
        },
        // The WebView goes with its screen (Back finishes the activity, which disposes this composition). Nothing used
        // to destroy it, so the WebView, its page and the renderer process behind it lived on until the garbage
        // collector reached them. The page going away does not touch the daemon's work, so this only frees the memory
        // and the renderer sooner. Loading is stopped first, then the WebView is destroyed: it is never used again.
        onRelease = { webView ->
            if (webViewRef === webView) webViewRef = null
            webView.stopLoading()
            webView.destroy()
        },
    )
}
