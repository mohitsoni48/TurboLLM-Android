package com.turbollm.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "TurboLLM"
private const val CHECK_MS = 2000L
private const val PREF_ANSWERED = "answered_for_folders"

/** Explains, and offers a way out, when a model folder in shared storage is configured that the app cannot read.
 *
 *  A model folder such as /sdcard/AI is added in the web UI, which cannot ask Android for anything. Without access the
 *  folder is not unreadable but quietly empty: Android 11+ shows an app only media files in shared storage, so the scan
 *  finds no models and reports no error. The Play build does not request All files access (a restricted permission),
 *  so on Android 11+ the way in is importing: the dialog opens the system file picker and ModelImport copies the chosen
 *  models into the app. Android 10 and older still have the ordinary storage permission, which is asked for instead,
 *  followed by the rescan the user would otherwise have to find.
 *
 *  The activity watches the daemon's config while it is on screen, and asks at most once per folder set per launch,
 *  so "Not now" sticks until the folders change or the app restarts. */
class StorageAccess(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val config = File(activity.filesDir, ".turbollm/config.json")
    private var configStamp = -1L
    private var sharedDirs: List<String> = emptyList()
    private var askedFor: List<String>? = null
    private val prefs = activity.getSharedPreferences("storage_access", Activity.MODE_PRIVATE)
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
        // An activity going away must not leak its window. A dialog still unanswered is asked again on return;
        // one already answered (Import, Allow or Not now) stays answered.
        if (dialog?.isShowing == true) {
            dialog?.dismiss()
            askedFor = null
        }
        dialog = null
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
        // An answer (Import, Allow or Not now) is kept across launches for this set of folders, so the dialog does not
        // come back on every cold start; adding or removing a shared folder asks again.
        val key = sharedDirs.sorted().joinToString("\n")
        if (prefs.getString(PREF_ANSWERED, null) == key) return
        askedFor = sharedDirs
        val answered = { prefs.edit().putString(PREF_ANSWERED, key).apply() }
        val folders = sharedDirs.joinToString(", ")
        dialog = if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(activity)
                .setTitle("Import your models")
                .setMessage(
                    "Android doesn't let TurboLLM read the model files in $folders, so that folder will look empty. " +
                        "Import the models instead: pick the .gguf or .litertlm files, and they're copied into the app.",
                )
                .setPositiveButton("Import models") { _, _ -> answered(); (activity as? MainActivity)?.startModelImport() }
                .setNegativeButton("Not now") { _, _ -> answered() }
                .show()
        } else {
            AlertDialog.Builder(activity)
                .setTitle("Allow access to your model folder")
                .setMessage("TurboLLM can't see the models in $folders until you allow access to your files.")
                .setPositiveButton("Allow") { _, _ -> answered(); request() }
                .setNegativeButton("Not now") { _, _ -> answered() }
                .show()
        }
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
        // Canonical forms: /sdcard is a link to /storage/emulated/0 (via /storage/self/primary), so the app's own
        // /sdcard/Android/data/<pkg> must be recognised whichever spelling the folder was added with.
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val own = activity.getExternalFilesDir(null)?.parentFile?.let { runCatching { it.canonicalPath }.getOrNull() }
        if (own != null && (canonical == own || canonical.startsWith("$own/"))) return false
        val external = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrDefault("/storage/emulated/0")
        return canonical.startsWith("/storage/") || canonical.startsWith("/mnt/") || canonical.startsWith(external) ||
            path.startsWith("/sdcard")
    }

    /** Android 10 and older: the storage runtime permissions. 11+: never, since All files access is not requested. */
    private fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 &&
            activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Read for scanning and loading; write too, because the daemon downloads into the primary model folder, which
     *  may be one of these. */
    private fun request() {
        activity.requestPermissions(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2,
        )
    }

    private fun rescan() {
        runCatching {
            val conn = URL("http://127.0.0.1:$ANDROID_DAEMON_PORT/api/v1/models/rescan").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-TurboLLM-App", DAEMON_APP_SECRET)
            conn.connectTimeout = 2000
            conn.readTimeout = 5000
            conn.responseCode
            conn.disconnect()
        }.onFailure { Log.w(TAG, "model rescan failed", it) }
    }
}
