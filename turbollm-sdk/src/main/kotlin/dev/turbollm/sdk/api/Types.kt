package dev.turbollm.sdk.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class ChatRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
data class ChatMessage(val role: ChatRole, val content: MessageContent)

/** OpenAI message content: a plain string, or an array of typed parts. */
@Serializable(with = MessageContentSerializer::class)
sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class Parts(val parts: List<ContentPart>) : MessageContent
}

/** Only [Text] is accepted in M1; any other part type is kept as [Unsupported] so it can be refused by name. */
@Serializable(with = ContentPartSerializer::class)
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Unsupported(val type: String) : ContentPart
}

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = false)

/**
 * OpenAI's token counts for one reply. When the reply ends at one of the request's stop sequences, the engine is
 * cancelled before it can count, so [completionTokens] is the number of text pieces streamed, an estimate of the
 * tokens generated; [promptTokens] is still the engine's own count.
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

/** llama-server's field names, so the TurboLLM daemon's speed readout needs no translation. */
@Serializable
data class Timings(
    @SerialName("prompt_n") val promptN: Int,
    @SerialName("prompt_ms") val promptMs: Double,
    @SerialName("predicted_n") val predictedN: Int,
    @SerialName("predicted_ms") val predictedMs: Double,
    @SerialName("predicted_per_second") val predictedPerSecond: Double,
)

internal object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): MessageContent {
        val json = decoder as JsonDecoder
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> MessageContent.Parts(json.json.decodeFromJsonElement(contentPartList, element))
            is JsonPrimitive -> MessageContent.Text(element.requireString("content"))
            else -> throw SerializationException("content must be a string or an array of parts")
        }
    }

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val json = encoder as JsonEncoder
        val element = when (value) {
            is MessageContent.Text -> JsonPrimitive(value.text)
            is MessageContent.Parts -> json.json.encodeToJsonElement(contentPartList, value.parts)
        }
        json.encodeJsonElement(element)
    }

    private val contentPartList = ListSerializer(ContentPart.serializer())
}

internal object ContentPartSerializer : KSerializer<ContentPart> {
    private const val TEXT_TYPE = "text"

    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): ContentPart {
        val part = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject
            ?: throw SerializationException("a content part must be an object")
        val type = part.stringField("type")
        return if (type == TEXT_TYPE) ContentPart.Text(part.stringField("text")) else ContentPart.Unsupported(type)
    }

    override fun serialize(encoder: Encoder, value: ContentPart) {
        val part = when (value) {
            is ContentPart.Text -> buildJsonObject { put("type", TEXT_TYPE); put("text", value.text) }
            is ContentPart.Unsupported -> buildJsonObject { put("type", value.type) }
        }
        (encoder as JsonEncoder).encodeJsonElement(part)
    }

    private fun JsonObject.stringField(name: String): String =
        get(name)?.jsonPrimitive?.requireString(name) ?: throw SerializationException("content part has no '$name'")
}

/** OpenAI `stop`: one string or a list of strings; always a list in Kotlin. */
internal object StopSequencesSerializer : KSerializer<List<String>> {
    private val stopList = ListSerializer(String.serializer())

    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as JsonDecoder
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> json.json.decodeFromJsonElement(stopList, element)
            is JsonPrimitive -> listOf(element.requireString("stop"))
            else -> throw SerializationException("stop must be a string or an array of strings")
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(json.json.encodeToJsonElement(stopList, value))
    }
}

private fun JsonPrimitive.requireString(field: String): String =
    if (isString) content else throw SerializationException("$field must be a string")
