package com.turbollm.app

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
    private var startedNodeAlready = false
    private val nodeRuntime = NodeRuntimeLauncher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The daemon ships inside the APK and is unpacked here, so a Play Store install needs
        // nothing pushed to the device. Synchronous on purpose: the Node thread started below
        // runs files/daemon/dist/cli.js, which has to exist by then.
        extractDaemonFromAssets()

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

    /** Unpack the daemon that ships inside the APK into `files/daemon/`, so a Play Store
     *  install works with nothing ever pushed to the device.
     *
     *  This replaces a `/sdcard` copy that was only ever a dev helper. That approach cannot work
     *  for a real user twice over: the app declares no storage permission, and nothing would have
     *  put the files there anyway. It also hid a second, larger problem — the daemon bundle the
     *  npm build produces leaves `dependencies` external (npm installs them on a desktop), so a
     *  genuinely fresh install died at boot with `Cannot find package '@hono/node-server'`. The
     *  payload staged into assets is therefore built by a separate Android pass with its
     *  dependencies bundled in (turbollm/tsup.android.config.ts + scripts/stage-daemon-assets.mjs).
     *
     *  Re-extracted whenever the APK changes, keyed on `lastUpdateTime`: an app update ships a new
     *  daemon, and a stale copy in filesDir would silently win over it. Everything else in
     *  filesDir — crucially `~/.turbollm`, the user's models and chat history — lives outside
     *  `daemon/` and is untouched. */
    private fun extractDaemonFromAssets() {
        val daemonDir = File(filesDir, "daemon")
        val stamp = File(daemonDir, ".apk-stamp")
        val installedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()

        if (stamp.exists() && stamp.readText() == installedAt) {
            Log.i(TAG, "daemon assets already extracted for this build")
            return
        }

        Log.i(TAG, "extracting daemon assets (apk stamp $installedAt)…")
        val started = System.currentTimeMillis()
        try {
            daemonDir.deleteRecursively()
            copyAssetDir("daemon", daemonDir)
            stamp.writeText(installedAt)
            Log.i(TAG, "daemon assets extracted in ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            // Leave no stamp behind, so the next launch retries rather than booting against a
            // half-written tree that would fail in a far more confusing way.
            Log.e(TAG, "Failed to extract daemon assets", e)
            runCatching { stamp.delete() }
        }
    }

    /** Recursive asset copy. `AssetManager.list()` returns an empty array for files and a
     *  non-empty one for directories, which is the only way to tell them apart. */
    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { inp ->
                FileOutputStream(dest).use { out -> inp.copyTo(out) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(dest, child))
    }
}
