package dev.turbollm.sdk.server

import dev.turbollm.sdk.api.ChatCompletionChunk
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.ChunkDelta
import dev.turbollm.sdk.api.ErrorBody
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.api.toErrorBody
import dev.turbollm.sdk.internal.sdkJson
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val DONE_DATA = "[DONE]"
private const val PING_FRAME = ": ping\n\n"
private val ROLE_ONLY_DELTA = ChunkDelta(role = ChatRole.ASSISTANT)

/** What a streamed reply sends: a chunk, a keep-alive ping while the client waits, or the failure that ends it. */
internal sealed interface StreamEvent {
    data class Chunk(val chunk: ChatCompletionChunk) : StreamEvent
    data object Ping : StreamEvent
    data class Failure(val error: TurboLLMException) : StreamEvent
}

/** The reply's chunks, ended by a [StreamEvent.Failure] instead of an exception when the reply fails. */
internal fun Flow<ChatCompletionChunk>.asStreamEvents(): Flow<StreamEvent> =
    map<ChatCompletionChunk, StreamEvent> { StreamEvent.Chunk(it) }
        .catch { failure -> emit(StreamEvent.Failure(failure.asReplyFailure())) }

/**
 * The events with a [StreamEvent.Ping] every [interval] while the request is queued, loading or prefilling
 * (ADR-347: proxies and clients drop a silent connection). Pings stop at the first generated event.
 */
internal fun Flow<StreamEvent>.withKeepAlive(interval: Duration): Flow<StreamEvent> = channelFlow {
    val pinger = launch { pingEvery(interval) }
    collect { event ->
        if (!event.announcesRoleOnly()) pinger.cancelAndJoin()
        send(event)
    }
    pinger.cancelAndJoin()
}

/**
 * Writes one reply as Server-Sent Events: `data: <json>` frames, `: ping` comments, and `data: [DONE]` last. The
 * status line is already sent, so a failure travels as a `data: {"error":{...}}` event.
 */
internal class SseWriter(private val channel: ByteWriteChannel) {

    /**
     * Writes [first], then every event of [rest], then `[DONE]`. A write that fails (the client left) cancels
     * [rest], which cancels the reply and so the engine, even if nothing reports the failure further up.
     */
    suspend fun relay(first: StreamEvent?, rest: ReceiveChannel<StreamEvent>) = rest.consume {
        first?.let { write(it) }
        for (event in this) write(event)
        done()
    }

    private suspend fun write(event: StreamEvent) = when (event) {
        is StreamEvent.Chunk -> data(sdkJson.encodeToString(ChatCompletionChunk.serializer(), event.chunk))
        StreamEvent.Ping -> frame(PING_FRAME)
        is StreamEvent.Failure -> data(sdkJson.encodeToString(ErrorBody.serializer(), event.error.toErrorBody()))
    }

    private suspend fun done() = data(DONE_DATA)

    private suspend fun data(payload: String) = frame("data: $payload\n\n")

    private suspend fun frame(text: String) {
        channel.writeStringUtf8(text)
        channel.flush()
    }
}

private suspend fun ProducerScope<StreamEvent>.pingEvery(interval: Duration) {
    while (true) {
        delay(interval)
        send(StreamEvent.Ping)
    }
}

private fun StreamEvent.announcesRoleOnly(): Boolean =
    this is StreamEvent.Chunk && chunk.choices.singleOrNull()?.delta == ROLE_ONLY_DELTA

/**
 * The SDK's own failures as they are. An unload ends the generation with a cancellation the request itself did
 * not ask for, so it is reported as the model no longer being loaded; anything else is an engine failure.
 */
internal fun Throwable.asReplyFailure(): TurboLLMException = when (this) {
    is TurboLLMException -> this
    is CancellationException -> TurboLLMException.ModelNotLoaded(message ?: "The model was unloaded.")
    else -> TurboLLMException.EngineFailure(message ?: "The engine failed while generating.", this)
}
