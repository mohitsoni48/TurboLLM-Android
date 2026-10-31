package dev.turbollm.sdk

import android.content.Context
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.EngineFactory
import dev.turbollm.sdk.engine.EngineRegistry
import dev.turbollm.sdk.internal.Clock
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
            engines = EngineRegistry(
                llamaCpp = notInThisBuild(EngineKind.LLAMA_CPP, "llama.cpp"),
                liteRtLm = notInThisBuild(EngineKind.LITERT_LM, "LiteRT-LM"),
            ),
            fetcher = UrlConnectionFetcher(),
            transport = KtorTransport(),
            logger = androidLogger,
            clock = monotonicClock,
            coreDispatcher = Dispatchers.Default,
            ioDispatcher = Dispatchers.IO,
            freeBytes = File::getUsableSpace,
            defaultCacheDir = File(context.cacheDir, CACHE_FOLDER),
        )

        /** The engine adapters (llama.cpp over JNI, LiteRT-LM) are not wired yet; a load says so plainly. */
        private fun notInThisBuild(kind: EngineKind, name: String) = EngineFactory {
            throw TurboLLMException.EngineUnavailable(kind, "The $name engine is not part of this build.")
        }
    }
}
