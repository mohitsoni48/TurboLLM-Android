package dev.turbollm.sdk

import java.io.File

/**
 * How the process's one [TurboLLM] core is set up. Two parts of one app that ask for different configs are
 * refused rather than left to silently disagree.
 *
 * @property modelRoot app-chosen folder that holds the models; created if missing.
 * @property cacheDir LiteRT-LM's cache; null uses `context.cacheDir/turbollm`.
 * @property hfToken sent to the Hugging Face origin only.
 * @property autoLoadOnRequest a chat naming an installed-but-unloaded model loads it.
 * @property allowLoadByPath allows [ModelRef.File] and `POST /sdk/v1/load {path}`; the TurboLLM app sets it.
 */
data class TurboLLMConfig(
    val modelRoot: File,
    val cacheDir: File? = null,
    val hfToken: String? = null,
    val hfBaseUrl: String = "https://huggingface.co",
    val autoLoadOnRequest: Boolean = false,
    val allowLoadByPath: Boolean = false,
    val maxQueuedRequests: Int = 8,
)
