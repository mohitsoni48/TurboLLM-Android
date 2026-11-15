package com.turbollm.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** What the daemon records in `daemon-fatal.json` when it cannot start (the file named by TURBOLLM_DAEMON_FATAL_FILE).
 *  Today only `core_unavailable`, exit code 69: the native core is missing from this build. Node runs inside the app
 *  process, so that exit ends the app; the record is how the next start knows not to run Node again (plan P-13). */
data class DaemonFatal(val code: String, val exitCode: Int, val message: String, val at: String)

private const val VERSION_CODE_KEY = "versionCode"

/** The record the daemon left, or null when there is none to act on.
 *
 *  The daemon does not know the app's version, so the first read stamps the record with [currentVersionCode]. A record
 *  stamped by another version is stale (a new APK gets a fresh try), and so is one that cannot be read: both are
 *  deleted and give null. */
fun readDaemonFatal(file: File, currentVersionCode: Long): DaemonFatal? {
    if (!file.exists()) return null
    val json = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
    val fatal = json?.let(::fatalOf)
    if (json == null || fatal == null) {
        clearDaemonFatal(file)
        return null
    }
    val recordedVersion = json[VERSION_CODE_KEY]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }
    return when {
        recordedVersion == null -> {
            runCatching {
                file.writeText(JsonObject(json + (VERSION_CODE_KEY to JsonPrimitive(currentVersionCode))).toString())
            }
            fatal
        }
        recordedVersion == currentVersionCode -> fatal
        else -> {
            clearDaemonFatal(file)
            null
        }
    }
}

fun clearDaemonFatal(file: File) {
    runCatching { file.delete() }
}

private fun fatalOf(json: JsonObject): DaemonFatal? = runCatching {
    DaemonFatal(
        code = json.getValue("code").jsonPrimitive.content,
        exitCode = json.getValue("exitCode").jsonPrimitive.int,
        message = json.getValue("message").jsonPrimitive.content,
        at = json["at"]?.jsonPrimitive?.content.orEmpty(),
    )
}.getOrNull()
