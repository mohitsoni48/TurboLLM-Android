package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.TurboLLMException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CpuVariantTest {

    @Test
    fun anArmv9CoreWithI8mmPrefersI8mmThenDotprodThenBase() {
        assertEquals(
            listOf(CpuVariant.I8MM, CpuVariant.DOTPROD, CpuVariant.BASE),
            CpuVariant.candidatesFor(cpuinfo("armv9")),
        )
    }

    @Test
    fun theS21FeHasDotprodButNoI8mm() {
        assertEquals(listOf(CpuVariant.DOTPROD, CpuVariant.BASE), CpuVariant.candidatesFor(cpuinfo("s21fe")))
    }

    @Test
    fun aPlainArmv80CoreGetsOnlyBase() {
        assertEquals(listOf(CpuVariant.BASE), CpuVariant.candidatesFor(cpuinfo("armv80")))
    }

    @Test
    fun anX86CpuHasNoFeaturesLineAndGetsOnlyBase() {
        assertEquals(listOf(CpuVariant.BASE), CpuVariant.candidatesFor(cpuinfo("x86")))
    }

    @Test
    fun featuresAreMatchedAsWholeWordsNotSubstrings() {
        val sveI8mmOnly = "Features\t: fp asimd asimddp svei8mm\n"

        assertEquals(listOf(CpuVariant.DOTPROD, CpuVariant.BASE), CpuVariant.candidatesFor(sveI8mmOnly))
    }

    @Test
    fun i8mmWithoutDotprodIsNotEnoughForTheI8mmBuild() {
        val i8mmOnly = "Features\t: fp asimd i8mm\n"

        assertEquals(listOf(CpuVariant.BASE), CpuVariant.candidatesFor(i8mmOnly))
    }

    @Test
    fun aFeatureMissingOnAnyCoreIsNotUsed() {
        val mixedCores = "Features\t: fp asimd asimddp i8mm\n\nFeatures\t: fp asimd asimddp\n"

        assertEquals(listOf(CpuVariant.DOTPROD, CpuVariant.BASE), CpuVariant.candidatesFor(mixedCores))
    }

    @Test
    fun theBestVariantIsLoadedByItsLibraryName() {
        val loaded = mutableListOf<String>()

        val variant = CpuVariant.loadBest(cpuinfo("armv9")) { loaded += it }

        assertEquals(CpuVariant.I8MM, variant)
        assertEquals(listOf("turbollm_llama_i8mm"), loaded)
    }

    @Test
    fun aMissingI8mmLibraryFallsBackToDotprod() {
        val attempted = mutableListOf<String>()

        val variant = CpuVariant.loadBest(cpuinfo("armv9")) { library ->
            attempted += library
            if (library == "turbollm_llama_i8mm") throw UnsatisfiedLinkError("no $library")
        }

        assertEquals(CpuVariant.DOTPROD, variant)
        assertEquals(listOf("turbollm_llama_i8mm", "turbollm_llama_dotprod"), attempted)
    }

    @Test
    fun noLoadableVariantMeansTheLlamaCppEngineIsUnavailable() {
        val attempted = mutableListOf<String>()

        val thrown = assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            CpuVariant.loadBest(cpuinfo("armv9")) { library ->
                attempted += library
                throw UnsatisfiedLinkError("no $library")
            }
        }

        assertEquals(EngineKind.LLAMA_CPP, thrown.kind)
        assertEquals(listOf("turbollm_llama_i8mm", "turbollm_llama_dotprod", "turbollm_llama_base"), attempted)
    }

    private fun cpuinfo(device: String): String =
        checkNotNull(javaClass.getResourceAsStream("/cpuinfo/$device.txt")) { "no cpuinfo fixture $device" }
            .bufferedReader()
            .use { it.readText() }
}
