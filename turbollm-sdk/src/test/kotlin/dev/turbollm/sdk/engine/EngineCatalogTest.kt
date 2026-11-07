package dev.turbollm.sdk.engine

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineAvailability
import dev.turbollm.sdk.EngineId
import dev.turbollm.sdk.EngineInfo
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.testCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineCatalogTest {

    private val llama = FakeEngine(kind = EngineKind.LLAMA_CPP)
    private val liteRt = FakeEngine(kind = EngineKind.LITERT_LM)

    @Test
    fun listsTheEnginesInPreferenceOrderWithWhatEachReadsAndRunsOn() {
        val expected = listOf(
            EngineInfo(
                EngineId.LLAMA_CPP_CPU, "llama.cpp (CPU)", EngineKind.LLAMA_CPP,
                setOf(ModelFormat.GGUF), setOf(Backend.CPU), EngineAvailability.Available,
            ),
            EngineInfo(
                EngineId.LITERT_LM, "LiteRT-LM", EngineKind.LITERT_LM,
                setOf(ModelFormat.LITERTLM), setOf(Backend.GPU, Backend.CPU), EngineAvailability.Available,
            ),
        )

        assertEquals(expected, testCatalog().list())
    }

    @Test
    fun aProbeThatGivesAReasonMakesItsEngineUnavailable() {
        val catalog = testCatalog(liteRtLmProbe = { "No GPU here." })

        assertEquals(listOf(EngineAvailability.Available, unavailable("No GPU here.")), catalog.availabilities())
    }

    @Test
    fun aProbeThatThrowsCountsAsUnavailableWithTheExceptionMessage() {
        val catalog = testCatalog(llamaCppProbe = { throw IllegalStateException("cpuinfo exploded") })

        assertEquals(unavailable("cpuinfo exploded"), catalog.list().first().availability)
    }

    @Test
    fun withNoEngineNamedAGgufGoesToLlamaCppAndABundleToLiteRtLm() {
        val catalog = testCatalog()

        assertEquals(EngineId.LLAMA_CPP_CPU, catalog.resolve(ModelFormat.GGUF, requested = null).id)
        assertEquals(EngineId.LITERT_LM, catalog.resolve(ModelFormat.LITERTLM, requested = null).id)
    }

    @Test
    fun aNamedEngineThatReadsTheFormatIsTheOneUsed() {
        val slot = testCatalog().resolve(ModelFormat.LITERTLM, requested = EngineId.LITERT_LM)

        assertEquals(EngineId.LITERT_LM, slot.id)
    }

    @Test
    fun anEngineTheSdkDoesNotHaveIsAnUnknownEngineRefusal() {
        val refusal = assertThrows(TurboLLMException.InvalidRequest::class.java) {
            testCatalog().resolve(ModelFormat.GGUF, requested = EngineId("nope"))
        }

        assertEquals("unknown_engine", refusal.code)
        assertEquals("engine", refusal.param)
        assertEquals(400, refusal.httpStatus)
    }

    @Test
    fun liteRtLmAskedToRunAGgufIsAFormatMismatchThatNamesBoth() {
        val refusal = assertThrows(TurboLLMException.InvalidRequest::class.java) {
            testCatalog().resolve(ModelFormat.GGUF, requested = EngineId.LITERT_LM)
        }

        assertEquals("engine_format_mismatch", refusal.code)
        assertEquals("engine", refusal.param)
        assertEquals("LiteRT-LM (litert-lm) can't read GGUF models.", refusal.message)
    }

    @Test
    fun llamaCppAskedToRunABundleIsAFormatMismatchNamingTheBundleFormat() {
        val refusal = assertThrows(TurboLLMException.InvalidRequest::class.java) {
            testCatalog().resolve(ModelFormat.LITERTLM, requested = EngineId.LLAMA_CPP_CPU)
        }

        assertEquals("llama.cpp (CPU) (llama.cpp-cpu) can't read .litertlm models.", refusal.message)
    }

    @Test
    fun aNamedEngineThatCannotRunHereIsRefusedWithoutFallingBackToAnother() {
        val catalog = testCatalog(liteRtLmProbe = { "No LiteRT-LM here." })

        val refusal = assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            catalog.resolve(ModelFormat.LITERTLM, requested = EngineId.LITERT_LM)
        }

        assertEquals(EngineId.LITERT_LM, refusal.engineId)
        assertEquals(EngineKind.LITERT_LM, refusal.kind)
        assertEquals("No LiteRT-LM here.", refusal.message)
    }

    @Test
    fun withNoEngineNamedAndNoReaderAvailableTheFirstReaderExplainsWhy() {
        val catalog = testCatalog(llamaCppProbe = { "No llama.cpp library in this build runs on this CPU." })

        val refusal = assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            catalog.resolve(ModelFormat.GGUF, requested = null)
        }

        assertEquals(EngineId.LLAMA_CPP_CPU, refusal.engineId)
        assertEquals(EngineKind.LLAMA_CPP, refusal.kind)
        assertEquals("No llama.cpp library in this build runs on this CPU.", refusal.message)
    }

    @Test
    fun anEngineMarkedUnavailableStaysUnavailableForListingAndRouting() {
        val catalog = testCatalog()

        catalog.markUnavailable(EngineId.LITERT_LM, "The LiteRT-LM library did not link.")

        assertEquals(unavailable("The LiteRT-LM library did not link."), catalog.list()[1].availability)
        assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            catalog.resolve(ModelFormat.LITERTLM, requested = null)
        }
        assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            catalog.resolve(ModelFormat.LITERTLM, requested = EngineId.LITERT_LM)
        }
    }

    @Test
    fun eachListingRunsEachProbeOnceAndRemembersNothingButRuntimeFailures() {
        var llamaProbes = 0
        var liteRtProbes = 0
        val catalog = testCatalog(llamaCppProbe = { llamaProbes++; null }, liteRtLmProbe = { liteRtProbes++; null })

        catalog.list()
        catalog.list()
        catalog.markUnavailable(EngineId.LITERT_LM, "gone")
        catalog.list()

        assertEquals(3, llamaProbes)
        assertEquals(2, liteRtProbes)
    }

    @Test
    fun ggufModelsGoToTheLlamaCppEngine() {
        val catalog = testCatalog(llamaCpp = { llama }, liteRtLm = { liteRt })

        assertSame(llama, catalog.resolve(ModelFormat.GGUF, requested = null).factory.create())
    }

    @Test
    fun liteRtModelsGoToTheLiteRtLmEngine() {
        val catalog = testCatalog(llamaCpp = { llama }, liteRtLm = { liteRt })

        assertSame(liteRt, catalog.resolve(ModelFormat.LITERTLM, requested = null).factory.create())
    }

    @Test
    fun everyLoadGetsAFreshEngineFromTheFactory() {
        var created = 0
        val slot = testCatalog(llamaCpp = { created++; FakeEngine() }).resolve(ModelFormat.GGUF, requested = null)

        slot.factory.create()
        slot.factory.create()

        assertEquals(2, created)
    }

    private fun EngineCatalog.availabilities() = list().map { it.availability }

    private fun unavailable(reason: String) = EngineAvailability.Unavailable(reason)
}
