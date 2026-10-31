package dev.turbollm.sdk.internal

import android.util.Log

private const val LOG_TAG = "TurboLLM"

/** Where the SDK writes its diagnostic lines; injected so tests can see exactly what is logged. */
internal fun interface Logger {
    fun info(message: String)
}

/** The production logger: Android logcat, info level. */
internal val androidLogger = Logger { message -> Log.i(LOG_TAG, message) }
