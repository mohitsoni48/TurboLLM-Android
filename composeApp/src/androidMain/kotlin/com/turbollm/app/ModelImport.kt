package com.turbollm.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TurboLLM"

/** Copies model files the user picks with the system file picker into the app's own model folder.
 *
 *  Android shows an app nothing but media in shared storage (/sdcard/…) unless it holds All files access, which the
 *  Play build does not request. So a model already on the phone reaches the library by being imported: picked through
 *  the Storage Access Framework, which needs no permission, and copied to [modelsDir], which the daemon scans (it adds
 *  that folder itself in the app, see TurboLLM engines/seed.ts) and the engines can open by path. The cost is the
 *  file's size again until the user deletes the original.
 *
 *  - Each file is written to `<name>.import-part` and renamed only when complete, so an interrupted copy never shows up
 *    in the library. One left by a process that died mid-copy is swept at the next app start or import. The suffix is
 *    the import's own because [modelsDir] is also one of the daemon's model folders: a download the daemon writes there
 *    (a non-Hugging Face Import from URL once the user makes this folder the primary one) keeps its partial file as
 *    `<name>.part` at the top level, which may be live or paused when a sweep runs and must never be swept.
 *  - Free space is checked first; a name already in use by a different file gets a numbered suffix, never overwritten.
 *  - A partial wake lock is held for the copy, and EngineService counts a running import as busy, so leaving the app
 *    neither freezes the copy nor stops the service under it.
 *  - Every failure is caught and reported; none can take the app down. */
object ModelImport {
    private val MODEL_FILE = Regex("""\.(gguf|litertlm)$""", RegexOption.IGNORE_CASE)
    private val SPLIT_PART = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)
    /** Not `.part`: that is the daemon's download suffix, in the same folder (see the class comment). */
    private const val IMPORT_PART = ".import-part"
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun modelsDir(context: Context) = File(context.filesDir, "models")

    /** Delete `*.import-part` files: only ever left behind by a copy whose process died, since a live import renames or
     *  deletes its own. Safe whenever no import is running. A daemon download's `*.part` here is left alone: deleting
     *  it under a running download made its final rename fail with ENOENT, and a paused one resume from byte 0. */
    fun sweepPartialFiles(context: Context) {
        if (!running.get()) sweep(modelsDir(context))
    }

    private fun sweep(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(IMPORT_PART) }?.forEach { f ->
            if (f.delete()) Log.i(TAG, "removed an interrupted import: ${f.name}")
        }
    }

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
        val wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurboLLM:import")
            .apply { setReferenceCounted(false) }

        Thread({
            val done = mutableListOf<String>()
            val problems = mutableListOf<String>()
            var refreshed = true
            try {
                wakeLock.acquire(3 * 60 * 60 * 1000L)
                val dest = modelsDir(app).apply { mkdirs() }
                sweep(dest) // this import has written nothing yet, so any .import-part here is a dead one
                for ((i, uri) in uris.withIndex()) {
                    if (cancelled.get()) break
                    var label = uri.lastPathSegment ?: "file ${i + 1}"
                    var part: File? = null
                    try {
                        val (name, size) = describe(app, uri)
                        label = name
                        if (!MODEL_FILE.containsMatchIn(name)) {
                            problems += "$name is not a .gguf or .litertlm file"
                            continue
                        }
                        val existing = File(dest, name)
                        if (existing.exists() && size > 0 && existing.length() == size) {
                            done += name // already imported
                            continue
                        }
                        val free = StatFs(dest.path).availableBytes
                        if (size > 0 && size > free - 200L * 1024 * 1024) {
                            problems += "$name needs ${gb(size)}, only ${gb(free)} free"
                            continue
                        }
                        val target = freeName(dest, name)
                        main.post { status.text = "${i + 1} of ${uris.size}: $name"; bar.progress = 0 }
                        part = File(dest, "${target.name}$IMPORT_PART")
                        copyStream(app, uri, part, size, cancelled) { p -> main.post { bar.progress = p } }
                        if (!part.renameTo(target)) throw IllegalStateException("could not finish writing the file")
                        part = null
                        done += target.name
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "import of $label failed", e)
                        problems += "$label: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        part?.delete()
                    }
                }
                problems += missingSplitParts(dest, done)
                if (done.isNotEmpty()) refreshed = registerWithDaemon(dest)
            } catch (e: Exception) {
                Log.w(TAG, "import failed", e)
                problems += e.message ?: "the import failed"
            } finally {
                running.set(false)
                if (wakeLock.isHeld) wakeLock.release()
                main.post {
                    runCatching { dialog.dismiss() }
                    Toast.makeText(app, summary(done.size, problems, cancelled.get(), refreshed), Toast.LENGTH_LONG).show()
                }
            }
        }, "turbollm-import").start()
    }

    private fun summary(imported: Int, problems: List<String>, cancelled: Boolean, refreshed: Boolean): String {
        val parts = mutableListOf<String>()
        if (cancelled) parts += "Import cancelled"
        if (imported > 0) {
            parts += "Imported $imported model${if (imported == 1) "" else "s"}" +
                if (refreshed) "" else ", but the library could not be refreshed: tap Rescan on the Models screen"
        }
        parts += problems
        return parts.joinToString(". ").ifEmpty { "Nothing was imported" }
    }

    private fun copyStream(context: Context, uri: Uri, part: File, size: Long, cancelled: AtomicBoolean, progress: (Int) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("the file could not be opened")
        input.use { inp ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                var lastShown = 0L
                while (true) {
                    if (cancelled.get()) throw InterruptedException()
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (size > 0 && copied - lastShown > 8L * 1024 * 1024) {
                        lastShown = copied
                        progress((copied * 1000 / size).toInt())
                    }
                }
                if (size > 0 && copied != size) throw IllegalStateException("copied $copied of $size bytes")
            }
        }
    }

    /** `name`, or `name-2.ext`, `name-3.ext`… when a different file already has it. A split GGUF part keeps its name,
     *  because llama.cpp finds the other parts by it. */
    private fun freeName(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists() || SPLIT_PART.matches(name)) return first
        val dot = name.lastIndexOf('.')
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (true) {
            val candidate = File(dir, "$stem-$n$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /** A split GGUF (`name-00001-of-00003.gguf`) loads only with every part present. */
    private fun missingSplitParts(dir: File, imported: List<String>): List<String> {
        val sets = imported.mapNotNull { SPLIT_PART.matchEntire(it) }.associate { m ->
            m.groupValues[1] to m.groupValues[3].toInt()
        }
        return sets.mapNotNull { (stem, total) ->
            val missing = (1..total).filter { !File(dir, "$stem-%05d-of-%05d.gguf".format(it, total)).exists() }
            if (missing.isEmpty()) null
            else "$stem is split into $total parts and part${if (missing.size == 1) "" else "s"} " +
                "${missing.joinToString(", ")} ${if (missing.size == 1) "is" else "are"} missing: import all of them"
        }
    }

    /** Display name and size from the provider; the size is -1 when it does not say. */
    private fun describe(context: Context, uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "model"
                val size = if (c.columnCount < 2 || c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "model") to -1L
    }

    /** Add the folder to the daemon's model folders (a no-op when it is there already); that request also rescans.
     *  False when the daemon did not accept it, so the caller says so instead of reporting a silent success. */
    private fun registerWithDaemon(dir: File): Boolean = runCatching {
        val conn = DaemonAuth.open("/api/v1/modeldirs")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 2000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(JSONObject().put("dir", dir.absolutePath).toString().toByteArray()) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "could not register the imported-models folder", it) }.getOrDefault(false)

    private fun gb(bytes: Long) = String.format("%.1f GB", bytes / 1e9)
}
