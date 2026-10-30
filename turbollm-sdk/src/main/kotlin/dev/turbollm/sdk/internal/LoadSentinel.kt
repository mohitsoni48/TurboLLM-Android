package dev.turbollm.sdk.internal

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Marks a native model load in progress, so a load that crashed the process can be recognised on the next
 * start and not retried blindly (R-6; a native crash cannot be caught, ADR-432).
 */
internal class LoadSentinel(modelRoot: File) {

    private val sentinel = File(File(modelRoot, ".turbollm-sdk"), "loading.json")

    fun write(model: File) {
        sentinel.parentFile?.mkdirs()
        sentinel.writeText(sdkJson.encodeToString(LoadingRecord.serializer(), LoadingRecord(model.absolutePath)))
    }

    fun clear() {
        sentinel.delete()
    }

    /** The model a crashed load was loading, once; afterwards (or with no leftover) null. */
    fun consumeStale(): String? {
        if (!sentinel.exists()) return null
        val path = readLoadingPath()
        clear()
        return path
    }

    private fun readLoadingPath(): String? = try {
        sdkJson.decodeFromString(LoadingRecord.serializer(), sentinel.readText()).path
    } catch (unreadable: SerializationException) {
        null
    }
}

@Serializable
private data class LoadingRecord(val path: String)
