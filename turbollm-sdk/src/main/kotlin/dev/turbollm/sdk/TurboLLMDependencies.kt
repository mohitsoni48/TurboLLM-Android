package dev.turbollm.sdk

import android.content.Context
import android.os.Build
import dev.turbollm.sdk.engine.EngineCatalog
import dev.turbollm.sdk.engine.liteRtLmSlot
import dev.turbollm.sdk.engine.llamaCppCpuSlot
import dev.turbollm.sdk.engine.litert.AarLiteRtRuntime
import dev.turbollm.sdk.engine.litert.LiteRtLmEngine
import dev.turbollm.sdk.engine.llama.CpuVariant
import dev.turbollm.sdk.engine.llama.JniLlamaBinding
import dev.turbollm.sdk.engine.llama.LlamaCppEngine
import dev.turbollm.sdk.engine.llama.readCpuinfo
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
    val engines: EngineCatalog,
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
        /** The ABIs this module builds native code for: `abiFilters` in `turbollm-sdk/build.gradle.kts`. */
        val SHIPPED_ABIS = listOf("arm64-v8a", "x86_64")

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

        /**
         * llama.cpp (CPU) first, then LiteRT-LM. A fresh engine per load; creating one loads no native code, its
         * first load does, on its own thread. The probes only read the ABI list and cpuinfo.
         */
        fun productionEngines(
            logger: Logger,
            deviceAbis: () -> List<String> = { Build.SUPPORTED_ABIS.toList() },
            cpuinfo: () -> String = ::readCpuinfo,
        ): EngineCatalog {
            val abiProbe = { missingShippedAbi(deviceAbis()) }
            return EngineCatalog(
                listOf(
                    llamaCppCpuSlot(
                        factory = { LlamaCppEngine(JniLlamaBinding(logger), DeviceInfo::defaultThreadCount) },
                        probe = { abiProbe() ?: missingCpuVariant(cpuinfo()) },
                    ),
                    liteRtLmSlot(
                        factory = { LiteRtLmEngine(AarLiteRtRuntime, DeviceInfo::defaultThreadCount, monotonicClock) },
                        probe = abiProbe,
                    ),
                ),
            )
        }

        private fun missingShippedAbi(deviceAbis: List<String>): String? =
            if (deviceAbis.any { it in SHIPPED_ABIS }) {
                null
            } else {
                "This SDK build has no native code for this device's ABIs (${deviceAbis.joinToString()})."
            }

        private fun missingCpuVariant(cpuinfo: String): String? =
            if (CpuVariant.candidatesFor(cpuinfo).isEmpty()) "No llama.cpp library in this build runs on this CPU." else null
    }
}
