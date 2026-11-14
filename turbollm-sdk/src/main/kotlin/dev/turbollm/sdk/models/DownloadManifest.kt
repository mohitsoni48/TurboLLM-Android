package dev.turbollm.sdk.models

import dev.turbollm.sdk.internal.sdkJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

private const val MANIFEST_PATH = ".turbollm-sdk/downloads.json"

/**
 * The SDK's own download rows, kept at `<root>/.turbollm-sdk/downloads.json` so a restart can bring them back.
 * Separate from the TurboLLM daemon's manifest, which lives elsewhere and is never written here.
 */
internal class DownloadManifest(root: File) {
    private val file = File(root, MANIFEST_PATH)

    /** The saved rows; none when the manifest is missing or unreadable. */
    fun load(): List<ManifestRow> {
        if (!file.isFile) return emptyList()
        return try {
            sdkJson.decodeFromString(ManifestFile.serializer(), file.readText()).rows
        } catch (unreadable: SerializationException) {
            emptyList()
        } catch (unreadable: IOException) {
            emptyList()
        }
    }

    /** Written to a temporary file and moved over the old one, so a reader never sees half a manifest. */
    fun save(rows: List<ManifestRow>) {
        file.parentFile?.mkdirs()
        val written = File(file.path + ".tmp")
        written.writeText(sdkJson.encodeToString(ManifestFile.serializer(), ManifestFile(rows)))
        Files.move(
            written.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

@Serializable
internal data class ManifestFile(val rows: List<ManifestRow>)

/** [sha] is the commit the files are fetched at; [path] the first shard, relative to the model root. */
@Serializable
internal data class ManifestRow(
    val id: String,
    val repo: String,
    val file: String,
    val sha: String? = null,
    val path: String? = null,
    val total: Long = 0,
    val state: String,
    val shards: List<ManifestShard> = emptyList(),
    val validator: String? = null,
)

@Serializable
internal data class ManifestShard(val path: String, val size: Long, val sha256: String? = null)
