package dev.turbollm.sdk.internal

import java.io.File
import java.io.IOException

/** What the device offers llama.cpp's compute threads. */
internal object DeviceInfo {
    private const val MAX_THREADS = 8
    private val CPU_ROOT = File("/sys/devices/system/cpu")
    private val CORE_FOLDER = Regex("cpu\\d+")

    /** The default llama.cpp thread count: the performance cores, clamped to 1..8. */
    fun defaultThreadCount(): Int =
        threadCountFor(maxFrequenciesKhz(CPU_ROOT), Runtime.getRuntime().availableProcessors())

    /**
     * The cores faster than the slowest cluster, since a phone's efficiency cores slow a decode down rather
     * than speed it up. When every core runs at one speed, or none could be read, every processor counts.
     */
    fun threadCountFor(coreMaxFrequenciesKhz: List<Long>, processors: Int): Int =
        performanceCores(coreMaxFrequenciesKhz, processors).coerceIn(1, MAX_THREADS)

    /** Each core's `cpufreq/cpuinfo_max_freq` under [cpuRoot]; a core without a readable one is left out. */
    fun maxFrequenciesKhz(cpuRoot: File): List<Long> =
        cpuRoot.listFiles { folder -> folder.isDirectory && CORE_FOLDER.matches(folder.name) }
            .orEmpty()
            .mapNotNull(::maxFrequencyOf)

    private fun performanceCores(coreMaxFrequenciesKhz: List<Long>, processors: Int): Int {
        val slowest = coreMaxFrequenciesKhz.minOrNull() ?: return processors
        val faster = coreMaxFrequenciesKhz.count { it > slowest }
        return if (faster == 0) coreMaxFrequenciesKhz.size else faster
    }

    private fun maxFrequencyOf(core: File): Long? =
        try {
            File(core, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
        } catch (_: IOException) {
            null
        }
}
