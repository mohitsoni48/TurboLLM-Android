package dev.turbollm.sdk.engine.llama

/**
 * The coarse JNI calls into the native llama session (architecture §2.6). Only primitives, [String],
 * [ByteArray] and opaque `Long` session handles cross the boundary. Every call except [requestAbort]
 * is made from the engine thread.
 *
 * No native exception reaches the JVM. A failed call returns a sentinel instead, and [lastError] then
 * says why: [loadModel] 0, [prepare] and [startSampling] a negative constant below, [modelInfo],
 * [parseDelta] and [stats] an empty string, and [nextToken] null. Every call except [lastError] resets
 * the message, so a null from [nextToken] with an empty [lastError] is the normal end of the reply.
 * A handle that was never issued, or was already freed, is refused like any other failure; [freeSession]
 * and [requestAbort] simply do nothing with it.
 */
internal object LlamaNative {
    const val FAILED = -1
    const val CONTEXT_LENGTH_EXCEEDED = -2
    const val TEMPLATE_UNSUPPORTED = -3
    const val ABORTED = -4

    external fun backendInit()

    /** 0 on failure, see [lastError]. */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): Long

    /** JSON: `n_ctx`, `n_ctx_train`, `desc`. */
    external fun modelInfo(session: Long): String

    /**
     * Renders the chat template and prefills the prompt, reusing the cached prefix.
     * [messagesJson] is `{"messages":[...],"enable_thinking":<bool>,"chat_template_kwargs":{"<k>":"<json>"}}`.
     * Returns the prompt token count, or a negative sentinel.
     */
    external fun prepare(session: Long, messagesJson: String): Int

    /** A negative [seed] picks a random one; `temp == 0` samples greedily. */
    external fun startSampling(session: Long, temp: Float, topP: Float, topK: Int, seed: Long): Int

    /** The token's UTF-8 bytes; null once the reply has ended; empty for a control token. */
    external fun nextToken(session: Long): ByteArray?

    /** JSON `{"content":"…","reasoning":"…"}`: what is new since the previous call. */
    external fun parseDelta(session: Long, isPartial: Boolean): String

    /** Sets the session's abort flag. Safe from any thread. */
    external fun requestAbort(session: Long)

    external fun clearAbort(session: Long)

    /** JSON: `prompt_n`, `prompt_decoded`, `prompt_ms`, `predicted_n`, `predicted_ms`, `context_full`. */
    external fun stats(session: Long): String

    /**
     * Frees the sampler, the templates, the context and the model, in that order. Idempotent. A call still
     * running on the session is aborted first, and the free returns only after that call has returned.
     */
    external fun freeSession(session: Long)

    /** The calling thread's last failure message. */
    external fun lastError(): String
}
