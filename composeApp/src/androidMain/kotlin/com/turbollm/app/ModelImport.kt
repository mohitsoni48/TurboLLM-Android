package com.turbollm.app

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TurboLLM"

/** Copies model files the user picks with the system file picker into the app's own model folder.
 *
 *  Android shows an app nothing but media in shared storage (/sdcard/…) unless it holds All files access, which the
 *  Play build does not request. So a model already on the phone reaches the library by being imported: picked through
 *  the Storage Access Framework, which needs no permission, and copied to [modelsDir], which the daemon scans and the
 *  engines can open by path. The cost is the file's size again until the user deletes the original.
 *
 *  Each file is written to `<name>.part` and renamed only when complete, so an interrupted copy never shows up in the
 *  library as a model. Free space is checked first. The folder is then registered with the daemon (which rescans). */
object ModelImport {
    private val MODEL_FILE = Regex("""\.(gguf|litertlm)$""", RegexOption.IGNORE_CASE)
    private val running = AtomicBoolean(false)

    fun modelsDir(activity: Activity) = File(activity.filesDir, "models")

    fun copy(activity: Activity, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(activity, "An import is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        val status = TextView(activity).apply { setPadding(0, 0, 0, 24) }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Importing models")
            .setView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(64, 32, 64, 0)
                addView(status)
                addView(bar)
            })
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()

        Thread({
            val dest = modelsDir(activity).apply { mkdirs() }
            val done = mutableListOf<String>()
            val problems = mutableListOf<String>()
            try {
                for ((i, uri) in uris.withIndex()) {
                    if (cancelled.get()) break
                    val (name, size) = describe(activity, uri)
                    if (!MODEL_FILE.containsMatchIn(name)) {
                        problems += "$name is not a .gguf or .litertlm file"
                        continue
                    }
                    val target = File(dest, name)
                    if (target.exists() && size > 0 && target.length() == size) {
                        done += name // already imported, identical size
                        continue
                    }
                    val free = StatFs(dest.path).availableBytes
                    if (size > 0 && size > free - 200L * 1024 * 1024) {
                        problems += "$name needs ${gb(size)}, only ${gb(free)} free"
                        continue
                    }
                    main.post { status.text = "${i + 1} of ${uris.size}: $name"; bar.progress = 0 }
                    val part = File(dest, "$name.part")
                    try {
                        activity.contentResolver.openInputStream(uri)!!.use { input ->
                            part.outputStream().use { output ->
                                val buf = ByteArray(1 shl 20)
                                var copied = 0L
                                var lastShown = 0L
                                while (true) {
                                    if (cancelled.get()) throw InterruptedException()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    copied += n
                                    if (size > 0 && copied - lastShown > 8L * 1024 * 1024) {
                                        lastShown = copied
                                        val p = (copied * 1000 / size).toInt()
                                        main.post { bar.progress = p }
                                    }
                                }
                            }
                        }
                        if (!part.renameTo(target)) throw IllegalStateException("could not finish writing $name")
                        done += name
                    } catch (e: InterruptedException) {
                        part.delete()
                        break
                    } catch (e: Exception) {
                        part.delete()
                        Log.w(TAG, "import of $name failed", e)
                        problems += "$name: ${e.message ?: "copy failed"}"
                    }
                }
                if (done.isNotEmpty()) registerWithDaemon(dest)
            } finally {
                running.set(false)
                main.post {
                    runCatching { dialog.dismiss() }
                    val msg = when {
                        cancelled.get() -> "Import cancelled"
                        problems.isEmpty() -> "Imported ${done.size} model${if (done.size == 1) "" else "s"}"
                        else -> (if (done.isNotEmpty()) "Imported ${done.size}. " else "") + problems.joinToString("; ")
                    }
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }
        }, "turbollm-import").start()
    }

    /** Display name and size from the provider; the size is -1 when it does not say. */
    private fun describe(activity: Activity, uri: Uri): Pair<String, Long> {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "model"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/') ?: "model") to -1L
    }

    /** Add the folder to the daemon's model folders (a no-op when it is there already); that request also rescans. */
    private fun registerWithDaemon(dir: File) {
        runCatching {
            val conn = URL("http://127.0.0.1:$ANDROID_DAEMON_PORT/api/v1/modeldirs").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 2000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(JSONObject().put("dir", dir.absolutePath).toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        }.onFailure { Log.w(TAG, "could not register the imported-models folder", it) }
    }

    private fun gb(bytes: Long) = String.format("%.1f GB", bytes / 1e9)
}
