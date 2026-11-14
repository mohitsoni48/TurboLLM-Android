package dev.turbollm.sdk.internal

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Marks a native model load in progress, so a load that crashed the process can be recognised on the next
 * start and not retried blindly (R-6; a native crash cannot be caught, ADR-432). Each record names its [owner],
 * so a core clears only the record it wrote, never one a later core in the same process has written since.
 */
internal class LoadSentinel(modelRoot: File, private val owner: String = UUID.randomUUID().toString()) {

    private val sentinel = File(File(modelRoot, ".turbollm-sdk"), "loading.json")

    /**
     * Best effort: true once the record is on disk. A sentinel that cannot be written never stops the load; it
     * only means a crash during that load goes unrecorded.
     */
    fun write(model: File): Boolean = try {
        sentinel.parentFile?.mkdirs()
        val record = LoadingRecord(model.absolutePath, owner)
        sentinel.writeText(sdkJson.encodeToString(LoadingRecord.serializer(), record))
        true
    } catch (unwritable: IOException) {
        false
    } catch (refused: SecurityException) {
        false
    }

    fun clear() {
        if (readRecord()?.owner == owner) sentinel.delete()
    }

    /** The model a crashed load was loading, once; afterwards (or with no leftover) null. */
    fun consumeStale(): String? {
        if (!sentinel.exists()) return null
        val path = readRecord()?.path
        sentinel.delete()
        return path
    }

    private fun readRecord(): LoadingRecord? = try {
        sdkJson.decodeFromString(LoadingRecord.serializer(), sentinel.readText())
    } catch (unreadable: SerializationException) {
        null
    } catch (gone: IOException) {
        null
    }
}

@Serializable
private data class LoadingRecord(val path: String, val owner: String = "")
