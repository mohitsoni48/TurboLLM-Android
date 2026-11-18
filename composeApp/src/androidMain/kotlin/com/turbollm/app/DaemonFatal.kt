package com.turbollm.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the daemon records when it cannot start (in the file named by TURBOLLM_DAEMON_FATAL_FILE). Today only
 *  `core_unavailable`, exit code 69: the native core is missing from this build. Node runs inside the app process, so
 *  that exit ends the app; the record is how the next start knows not to run Node again (plan P-13). */
data class DaemonFatal(val code: String, val exitCode: Int, val message: String, val at: String)

/** The daemon's fatal record for one install of the app.
 *
 *  The daemon does not know which install it runs in, so the app names the file after it: the version code plus the
 *  package's `lastUpdateTime`, which changes on every install or update, a same-version reinstall included. An update
 *  therefore looks for a file nobody wrote and gets a fresh try, and [sweepStale] removes the records of every other
 *  install. The cost, accepted by the founder: reinstalling the same broken APK fails to start once more before the
 *  screen returns. */
class DaemonFatalRecord(filesDir: File, versionCode: Long, lastUpdateTime: Long) {
    /** The path to give the daemon as TURBOLLM_DAEMON_FATAL_FILE. */
    val file = File(filesDir, "$PREFIX-$versionCode-$lastUpdateTime.json")

    /** Deletes every other install's record, and the unnamed `daemon-fatal.json` an earlier build used. */
    fun sweepStale() {
        file.parentFile?.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".json") && f != file }
            ?.forEach(::clearDaemonFatal)
    }

    fun read(): DaemonFatal? = readDaemonFatal(file)

    fun clear() = clearDaemonFatal(file)

    private companion object {
        const val PREFIX = "daemon-fatal"
    }
}

/** The record in [file], or null when there is none to act on. A file that cannot be read as a record (broken JSON, a
 *  field missing) is deleted: it would never become one. `at` is optional and reads as empty. */
fun readDaemonFatal(file: File): DaemonFatal? {
    if (!file.exists()) return null
    val fatal = runCatching { fatalOf(Json.parseToJsonElement(file.readText()).jsonObject) }.getOrNull()
    if (fatal == null) clearDaemonFatal(file)
    return fatal
}

fun clearDaemonFatal(file: File) {
    runCatching { file.delete() }
}

private fun fatalOf(json: JsonObject) = DaemonFatal(
    code = json.getValue("code").jsonPrimitive.content,
    exitCode = json.getValue("exitCode").jsonPrimitive.int,
    message = json.getValue("message").jsonPrimitive.content,
    at = json["at"]?.jsonPrimitive?.content.orEmpty(),
)
