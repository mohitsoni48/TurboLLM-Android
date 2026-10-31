package dev.turbollm.sdk

import android.content.Context
import dev.turbollm.sdk.engine.EngineRegistry
import dev.turbollm.sdk.engine.litert.AarLiteRtRuntime
import dev.turbollm.sdk.engine.litert.LiteRtLmEngine
import dev.turbollm.sdk.engine.llama.JniLlamaBinding
import dev.turbollm.sdk.engine.llama.LlamaCppEngine
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.DeviceInfo
import dev.turbollm.sdk.internal.Logger
import dev.turbollm.sdk.internal.androidLogger
import dev.turbollm.sdk.internal.monotonicClock
import dev.turbollm.sdk.models.HttpFetcher
import dev.turbollm.sdk.models.UrlConnectionFetcher
import dev.turbollm.sdk.server.HttpTransport
import dev.turbollm.sdk.server.KtorTransport
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private const val CACHE_FOLDER = "turbollm"

/** What the [TurboLLM] core runs on: the engines, the network, the socket, time and threads. Tests pass fakes. */
internal class TurboLLMDependencies(
    val engines: EngineRegistry,
    val fetcher: HttpFetcher,
    val transport: HttpTransport,
    val logger: Logger,
    val clock: Clock,
    val coreDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
    val freeBytes: (File) -> Long,
    val defaultCacheDir: File,
) {
    companion object {
        fun forAndroid(context: Context) = TurboLLMDependencies(
            engines = productionEngines(androidLogger),
            fetcher = UrlConnectionFetcher(),
            transport = KtorTransport(),
            logger = androidLogger,
            clock = monotonicClock,
            coreDispatcher = Dispatchers.Default,
            ioDispatcher = Dispatchers.IO,
            freeBytes = File::getUsableSpace,
            defaultCacheDir = File(context.cacheDir, CACHE_FOLDER),
        )

        /** A fresh engine per load. Creating one loads no native code; its first load does, on its own thread. */
        fun productionEngines(logger: Logger) = EngineRegistry(
            llamaCpp = { LlamaCppEngine(JniLlamaBinding(logger), DeviceInfo::defaultThreadCount) },
            liteRtLm = { LiteRtLmEngine(AarLiteRtRuntime, DeviceInfo::defaultThreadCount, monotonicClock) },
        )
    }
}
