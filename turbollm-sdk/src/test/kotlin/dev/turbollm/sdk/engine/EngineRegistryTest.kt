package dev.turbollm.sdk.engine

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.testing.FakeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineRegistryTest {

    private val llama = FakeEngine(kind = EngineKind.LLAMA_CPP)
    private val liteRt = FakeEngine(kind = EngineKind.LITERT_LM)

    @Test
    fun ggufModelsGoToTheLlamaCppEngine() {
        val registry = EngineRegistry(llamaCpp = { llama }, liteRtLm = { liteRt })

        assertSame(llama, registry.engineFor(ModelFormat.GGUF))
    }

    @Test
    fun liteRtModelsGoToTheLiteRtLmEngine() {
        val registry = EngineRegistry(llamaCpp = { llama }, liteRtLm = { liteRt })

        assertSame(liteRt, registry.engineFor(ModelFormat.LITERTLM))
    }

    @Test
    fun anEngineThatCannotRunOnThisDeviceIsReportedUnavailable() {
        val registry = EngineRegistry(
            llamaCpp = { throw TurboLLMException.EngineUnavailable(EngineKind.LLAMA_CPP) },
            liteRtLm = { liteRt },
        )

        val thrown = assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            registry.engineFor(ModelFormat.GGUF)
        }

        assertEquals(EngineKind.LLAMA_CPP, thrown.kind)
    }

    @Test
    fun everyRequestGetsAFreshEngineFromTheFactory() {
        var created = 0
        val registry = EngineRegistry(llamaCpp = { created++; FakeEngine() }, liteRtLm = { liteRt })

        registry.engineFor(ModelFormat.GGUF)
        registry.engineFor(ModelFormat.GGUF)

        assertEquals(2, created)
    }
}
