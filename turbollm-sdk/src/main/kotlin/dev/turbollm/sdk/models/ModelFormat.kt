package dev.turbollm.sdk.models

import dev.turbollm.sdk.ModelFormat
import java.io.File
import java.util.Locale

private const val GGUF_MIN_BYTES = 1L shl 20
private val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

/** litert_lm_builder's HEADER_MAGIC_BYTES, as the daemon checks it (ADR-447 point 2). */
private val LITERTLM_MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

/** The model format a file name claims, by extension; null when it names no model format. */
internal fun modelFormatOf(fileName: String): ModelFormat? {
    val lowerName = fileName.lowercase(Locale.ROOT)
    return when {
        lowerName.endsWith(".gguf") -> ModelFormat.GGUF
        lowerName.endsWith(".litertlm") -> ModelFormat.LITERTLM
        else -> null
    }
}

/** Vision projectors ride along with GGUF models; M1 has no vision, so they are never models of their own. */
internal fun isVisionProjector(fileName: String): Boolean = fileName.lowercase(Locale.ROOT).contains("mmproj")

/** True when the file starts with this format's magic, so a renamed error page or a stray file is not a model. */
internal fun ModelFormat.hasMagic(file: File): Boolean {
    val magic = magicBytes()
    val head = ByteArray(magic.size)
    val read = file.inputStream().use { it.readNBytesCompat(head) }
    return read == magic.size && head.contentEquals(magic)
}

/** A GGUF under 1 MiB is a truncated download, not a model; a LiteRT-LM test bundle can be tiny. */
internal fun ModelFormat.hasPlausibleSize(sizeBytes: Long): Boolean = when (this) {
    ModelFormat.GGUF -> sizeBytes >= GGUF_MIN_BYTES
    ModelFormat.LITERTLM -> true
}

private fun ModelFormat.magicBytes(): ByteArray = when (this) {
    ModelFormat.GGUF -> GGUF_MAGIC
    ModelFormat.LITERTLM -> LITERTLM_MAGIC
}

/** `InputStream.readNBytes(byte[], int, int)` needs API 33; minSdk is 24. */
private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}
