package dev.turbollm.sdk.engine

import dev.turbollm.sdk.Backend
import java.io.File

internal data class LoadSpec(
    val path: File,
    val contextSize: Int?,
    val threads: Int?,
    val backend: Backend,
    val cacheDir: File,
)
