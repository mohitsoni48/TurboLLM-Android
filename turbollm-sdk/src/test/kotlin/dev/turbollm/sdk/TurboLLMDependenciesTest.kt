package dev.turbollm.sdk

import dev.turbollm.sdk.engine.litert.LiteRtLmEngine
import dev.turbollm.sdk.engine.llama.LlamaCppEngine
import dev.turbollm.sdk.internal.Logger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboLLMDependenciesTest {

    private val engines = productionEnginesOn(abis = listOf("arm64-v8a", "armeabi-v7a"))

    @Test
    fun aGgufRunsOnTheLlamaCppEngineWithoutLoadingAnythingYet() {
        engines.resolve(ModelFormat.GGUF, requested = null).factory.create().use { engine ->
            assertTrue(engine is LlamaCppEngine)
            assertEquals(EngineKind.LLAMA_CPP, engine.kind)
        }
    }

    @Test
    fun aLiteRtLmBundleRunsOnTheLiteRtLmEngineWithoutLoadingAnythingYet() {
        engines.resolve(ModelFormat.LITERTLM, requested = null).factory.create().use { engine ->
            assertTrue(engine is LiteRtLmEngine)
            assertEquals(EngineKind.LITERT_LM, engine.kind)
        }
    }

    @Test
    fun productionListsLlamaCppCpuThenLiteRtLmBothAvailableOnAShippedAbi() {
        val listed = engines.list()

        assertEquals(listOf(EngineId.LLAMA_CPP_CPU, EngineId.LITERT_LM), listed.map { it.id })
        assertTrue(listed.all { it.availability == EngineAvailability.Available })
    }

    @Test
    fun onADeviceWhoseAbiThisBuildDoesNotShipNeitherEngineIsAvailable() {
        val listed = productionEnginesOn(abis = listOf("armeabi-v7a")).list()

        assertTrue(listed.all { it.availability is EngineAvailability.Unavailable })
    }

    @Test
    fun theShippedAbisMatchTheModuleAbiFilters() {
        val buildScript = File("build.gradle.kts").readText()
        val abiFilters = Regex("""abiFilters \+= listOf\(([^)]*)\)""").find(buildScript)!!.groupValues[1]
        val filtered = Regex("\"([^\"]+)\"").findAll(abiFilters).map { it.groupValues[1] }.toList()

        assertEquals(filtered, TurboLLMDependencies.SHIPPED_ABIS)
    }

    private fun productionEnginesOn(abis: List<String>) =
        TurboLLMDependencies.productionEngines(Logger { }, deviceAbis = { abis }, cpuinfo = { "" })
}
