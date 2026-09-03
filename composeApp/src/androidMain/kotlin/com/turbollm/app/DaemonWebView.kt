package com.turbollm.app

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 *  too, not just what exists at `onPageFinished` time. */
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

  function fixAll() {
    htmlFix();
    fixVhSubtree(document.documentElement);
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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!ready) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Starting TurboLLM…", modifier = Modifier.padding(top = 16.dp))
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

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(HEIGHT_FIX_JS, null)
                    }
                }
                loadUrl(DAEMON_URL)
                webViewRef = this
            }
        },
    )
}
