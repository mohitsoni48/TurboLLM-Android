package dev.turbollm.sdk.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelList(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelEntry>,
)

@Serializable
data class ModelEntry(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "turbollm-sdk",
    val meta: ModelMeta? = null,
)

@Serializable
data class ModelMeta(
    val format: String,
    val engine: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val loaded: Boolean,
)
