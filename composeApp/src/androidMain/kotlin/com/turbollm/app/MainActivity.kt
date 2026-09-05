package com.turbollm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
        // QA_BUGS.md BUG-14 workaround: copy updated daemon files from /sdcard/turbollm/ into
        // the app's private data dir. `run-as` on this emulator can read /sdcard but cannot
        // write into the app's own data directory (SELinux runas_app context blocks it), so
        // the app itself does the copy at startup when it runs with its full UID.
        pullDaemonFromSdcard()

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
                            // QA_BUGS.md BUG-01: applicationInfo.nativeLibraryDir is the ONLY
                            // place Android's W^X hardening exempts (build.gradle.kts's jniLibs
                            // comment) — and PackageManager regenerates it on every install/
                            // update (a fresh signing session even for the identical APK), so a
                            // path saved from a previous run is not reliable across app updates.
                            // The daemon reads this at boot to self-register/repair its bundled
                            // llama-server engine (engines/seed.ts's ensureAndroidBundledEngine)
                            // instead of depending on a manual one-time registration that goes
                            // stale the next time the app is reinstalled.
                            "TURBOLLM_ANDROID_NATIVE_LIB_DIR" to applicationInfo.nativeLibraryDir,
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

    /** Copy every file in /sdcard/ into files/daemon/dist/ if it's newer or missing.
     *  This is a dev-only helper — production would ship the daemon bundled in the APK.
     *  We copy from /sdcard/ root because run-as SELinux context blocks /sdcard/turbollm/. */
    private fun pullDaemonFromSdcard() {
        val src = File("/sdcard")
        Log.i(TAG, "pullDaemonFromSdcard: src.exists=${src.exists()}, isDirectory=${src.isDirectory}")
        if (!src.exists() || !src.isDirectory) {
            Log.w(TAG, "/sdcard does not exist or is not a directory")
            return
        }
        val dstDir = File(filesDir, "daemon/dist")
        dstDir.mkdirs()
        Log.i(TAG, "pullDaemonFromSdcard: dstDir=${dstDir.absolutePath}, exists=${dstDir.exists()}")
        src.listFiles()?.filter { it.isFile && (it.name == "cli.js" || it.name == "android-preload.cjs" || it.name.startsWith("chunk-") || it.name.startsWith("sql-js-") || it.name.startsWith("distiller-") || it.name.startsWith("resolve-executable-") || it.name.startsWith("run-code-") || it.name.startsWith("shell-command-") || it.name.startsWith("code-")) }?.forEach { file ->
            val dst = File(dstDir, file.name)
            Log.i(TAG, "pullDaemonFromSdcard: checking ${file.name}, dst.exists=${dst.exists()}, file.lastModified=${file.lastModified()}, dst.lastModified=${dst.lastModified()}")
            if (!dst.exists() || file.lastModified() > dst.lastModified()) {
                try {
                    FileInputStream(file).use { inp ->
                        FileOutputStream(dst).use { out -> inp.copyTo(out) }
                    }
                    Log.i(TAG, "Pulled daemon file: ${file.name} (${file.length()} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pull ${file.name}: ${e.message}")
                }
            }
        }
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
