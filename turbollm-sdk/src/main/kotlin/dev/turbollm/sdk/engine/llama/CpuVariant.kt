package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.TurboLLMException

/**
 * One build of the llama.cpp JNI library, tuned for a CPU feature level (architecture §2.5, §3.2).
 * Declared best first; x86_64 ships [BASE] only, and its `/proc/cpuinfo` has no `Features` line.
 */
internal enum class CpuVariant(private val requiredFeatures: Set<String>) {
    I8MM(setOf("asimddp", "i8mm")),
    DOTPROD(setOf("asimddp")),
    BASE(emptySet());

    val libraryName: String = "turbollm_llama_${name.lowercase()}"

    companion object {

        /**
         * Loads the best variant this CPU supports, falling down the list on `UnsatisfiedLinkError`.
         * [loadLibrary] is `System.loadLibrary` in production.
         */
        fun loadBest(cpuinfo: String, loadLibrary: (String) -> Unit = System::loadLibrary): CpuVariant =
            candidatesFor(cpuinfo).firstOrNull { it.loadsWith(loadLibrary) }
                ?: throw TurboLLMException.EngineUnavailable(
                    EngineKind.LLAMA_CPP,
                    "No llama.cpp library in this build runs on this CPU.",
                )

        /** The variants this CPU can run, best first. */
        fun candidatesFor(cpuinfo: String): List<CpuVariant> {
            val features = featuresOnEveryCore(cpuinfo)
            return entries.filter { features.containsAll(it.requiredFeatures) }
        }

        private fun featuresOnEveryCore(cpuinfo: String): Set<String> =
            cpuinfo.lineSequence()
                .filter { it.substringBefore(':').trim() == "Features" }
                .map { it.substringAfter(':').trim().split(WHITESPACE).toSet() }
                .reduceOrNull { common, core -> common intersect core }
                .orEmpty()

        private val WHITESPACE = Regex("\\s+")
    }

    private fun loadsWith(loadLibrary: (String) -> Unit): Boolean =
        try {
            loadLibrary(libraryName)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
}
