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

private const val DAEMON_URL = "http://127.0.0.1:6996/"

/** Confirmed live (2026-09-03) via Chrome DevTools attached over the WebView's own devtools
 *  socket: the app's CSS never sets an explicit height on html/body/#root (it doesn't need to
 *  on desktop Chrome — verified the loaded stylesheet has no such rule), and instead relies on
 *  `h-full`/`h-dvh` Tailwind utilities resolving through the viewport. In THIS WebView (Android
 *  System WebView, Chromium 145 on this emulator) those all compute to a literal 0px — DOM and
 *  CSS both load correctly (194 rules, zero console errors) but nothing ever gets a real
 *  height, so the whole app renders with zero pixels of visible content despite being fully
 *  mounted. Setting an explicit PIXEL height from `window.innerHeight` (not a CSS unit at all)
 *  sidesteps whatever WebView-specific dvh/vh-in-a-WebView-host resolution bug causes this —
 *  confirmed live this alone makes the real app appear. Re-applied on resize (keyboard,
 *  rotation) since a WebView-hosted page doesn't get the same dynamic-viewport recalculation a
 *  real browser tab does. */
private const val HEIGHT_FIX_JS = """
(function() {
  function fixHeight() {
    var px = window.innerHeight + 'px';
    document.documentElement.style.height = px;
    document.body.style.height = px;
    var root = document.getElementById('root');
    if (root) root.style.height = px;
  }
  fixHeight();
  window.addEventListener('resize', fixHeight);
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
            ready = client.isDaemonReachable()
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
