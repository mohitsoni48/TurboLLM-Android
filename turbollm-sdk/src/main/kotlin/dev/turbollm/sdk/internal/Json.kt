package dev.turbollm.sdk.internal

import kotlinx.serialization.json.Json

internal val sdkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
