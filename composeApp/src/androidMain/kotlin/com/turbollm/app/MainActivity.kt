package com.turbollm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

private const val TAG = "TurboLLM"

/** The daemon's listen port on Android — 7000, not desktop/LAN's 6996, to avoid any ambiguity
 *  between the two when both might be reachable on the same machine during testing (an
 *  emulator's forwarded port and a developer's own desktop daemon). Standard for Android
 *  testing and the app alike; used both for the --port flag the daemon is launched with
 *  (below) and for the WebView's own URL (DaemonWebView.kt). */
const val ANDROID_DAEMON_PORT = 7000

class MainActivity : ComponentActivity() {
    private companion object {
        const val CHANNEL_ID = "TurboLLM_Daemon"
    }

    private var startedNodeAlready = false
    private val nodeRuntime = NodeRuntimeLauncher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        if (!startedNodeAlready) {
            startedNodeAlready = true
            Thread {
                try {
                    // Spike D: the real daemon, manually staged onto the device for this
                    // verification pass (adb push + run-as) — not yet bundled into the APK's
                    // own assets (that's the "download/extract on first launch" step, later).
                    val daemonScript = "${filesDir.absolutePath}/daemon/dist/cli.js"
                    nodeRuntime.startScript(
                        daemonScript,
                        env = mapOf(
                            // os.homedir() has no real Unix $HOME to fall back to in an
                            // Android app process — the daemon resolves ~/.turbollm through
                            // it, so without this it can't find/create its data directory.
                            "HOME" to filesDir.absolutePath,
                            "TMPDIR" to cacheDir.absolutePath,
                        ),
                        args = arrayOf("--no-telemetry", "--port", ANDROID_DAEMON_PORT.toString()),
                        preloadScripts = arrayOf("${filesDir.absolutePath}/daemon/dist/android-preload.cjs"),
                    )
                    Log.i(TAG, "TurboLLM daemon started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start the daemon", e)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Daemon start failed: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }.start()
        }

        setContent { DaemonWebViewScreen() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TurboLLM Daemon",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "TurboLLM local LLM daemon service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
