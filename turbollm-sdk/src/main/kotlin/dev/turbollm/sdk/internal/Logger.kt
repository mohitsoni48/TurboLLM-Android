package dev.turbollm.sdk.internal

/** Where the SDK writes its diagnostic lines; injected so tests can see exactly what is logged. */
internal fun interface Logger {
    fun info(message: String)
}
