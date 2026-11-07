package dev.turbollm.sdk.testing

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.engine.EngineCatalog
import dev.turbollm.sdk.engine.EngineFactory
import dev.turbollm.sdk.engine.liteRtLmSlot
import dev.turbollm.sdk.engine.llamaCppCpuSlot

/** The production catalog's two slots, with fake factories and probes that say "runs here" unless told otherwise. */
internal fun testCatalog(
    llamaCpp: EngineFactory = EngineFactory { FakeEngine(kind = EngineKind.LLAMA_CPP) },
    liteRtLm: EngineFactory = EngineFactory { FakeEngine(kind = EngineKind.LITERT_LM) },
    llamaCppProbe: () -> String? = { null },
    liteRtLmProbe: () -> String? = { null },
) = EngineCatalog(listOf(llamaCppCpuSlot(llamaCpp, llamaCppProbe), liteRtLmSlot(liteRtLm, liteRtLmProbe)))
