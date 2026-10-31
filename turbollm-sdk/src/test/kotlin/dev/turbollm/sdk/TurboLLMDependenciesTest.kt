package dev.turbollm.sdk

import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.llama.LlamaCppEngine
import dev.turbollm.sdk.internal.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboLLMDependenciesTest {

    private val engines = TurboLLMDependencies.productionEngines(Logger { })

    @Test
    fun aGgufRunsOnTheLlamaCppEngineWithoutLoadingAnythingYet() {
        engines.engineFor(ModelFormat.GGUF).use { engine ->
            assertTrue(engine is LlamaCppEngine)
            assertEquals(EngineKind.LLAMA_CPP, engine.kind)
        }
    }

    @Test
    fun theLiteRtLmEngineIsStillNotPartOfThisBuild() {
        val failure = assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            engines.engineFor(ModelFormat.LITERTLM)
        }

        assertEquals(EngineKind.LITERT_LM, failure.kind)
    }
}
