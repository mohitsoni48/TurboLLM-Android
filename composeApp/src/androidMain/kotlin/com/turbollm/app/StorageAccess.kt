package com.turbollm.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "TurboLLM"
private const val CHECK_MS = 2000L

/** Asks for shared-storage access when a model folder in it is configured, then has the daemon rescan.
 *
 *  A model folder such as /sdcard/AI is added in the web UI, which cannot ask Android for anything. Without access
 *  the folder is not unreadable but quietly empty: Android 11+ shows an app only media files in shared storage, so
 *  the scan finds no models and reports no error. So the activity watches the daemon's config while it is on screen.
 *  When a shared-storage folder appears and access is missing, it explains and offers the system screen ("All files
 *  access" on 11+, the storage permission before that). When access arrives, it triggers the rescan the user would
 *  otherwise have to find.
 *
 *  It asks at most once per folder set per launch, so "Not now" sticks until the folders change or the app restarts. */
class StorageAccess(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val config = File(activity.filesDir, ".turbollm/config.json")
    private var configStamp = -1L
    private var sharedDirs: List<String> = emptyList()
    private var askedFor: List<String>? = null
    private var dialog: AlertDialog? = null
    private var hadAccess = hasAccess()

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    fun onResume() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun onPause() {
        handler.removeCallbacks(tick)
    }

    private fun check() {
        val access = hasAccess()
        if (access && !hadAccess) {
            Log.i(TAG, "shared-storage access granted; rescanning models")
            Thread { rescan() }.start()
        }
        hadAccess = access
        refreshDirs()
        if (access || sharedDirs.isEmpty() || askedFor == sharedDirs || dialog?.isShowing == true) return
        askedFor = sharedDirs
        dialog = AlertDialog.Builder(activity)
            .setTitle("Allow access to your model folder")
            .setMessage(
                "TurboLLM can't see the models in ${sharedDirs.joinToString(", ")}. Android hides files in shared " +
                    "storage from apps until you allow it." +
                    (if (Build.VERSION.SDK_INT >= 30) "\n\nOn the next screen, turn on \"Allow access to manage all files\"." else ""),
            )
            .setPositiveButton("Allow") { _, _ -> request() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun refreshDirs() {
        val stamp = config.lastModified()
        if (stamp == configStamp) return
        configStamp = stamp
        sharedDirs = runCatching {
            val dirs = JSONObject(config.readText()).optJSONArray("modelDirs")
            (0 until (dirs?.length() ?: 0)).map { dirs!!.getString(it) }.filter(::isSharedStorage)
        }.getOrDefault(emptyList())
    }

    /** Shared storage, as opposed to this app's own private directories, which need no permission. */
    private fun isSharedStorage(path: String): Boolean {
        val external = Environment.getExternalStorageDirectory().absolutePath
        val own = activity.getExternalFilesDir(null)?.parentFile?.absolutePath
        if (own != null && path.startsWith(own)) return false
        return path.startsWith("/sdcard") || path.startsWith("/storage/") || path.startsWith("/mnt/") || path.startsWith(external)
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun request() {
        if (Build.VERSION.SDK_INT >= 30) {
            val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(forApp) }
                .recoverCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { Log.w(TAG, "no all-files-access settings screen", it) }
        } else {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2,
            )
        }
    }

    private fun rescan() {
        runCatching {
            val conn = URL("http://127.0.0.1:$ANDROID_DAEMON_PORT/api/v1/models/rescan").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 5000
            conn.responseCode
            conn.disconnect()
        }.onFailure { Log.w(TAG, "model rescan failed", it) }
    }
}
