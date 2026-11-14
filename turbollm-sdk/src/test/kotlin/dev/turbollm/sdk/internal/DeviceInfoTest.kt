package dev.turbollm.sdk.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceInfoTest {

    @get:Rule
    val sysfs = TemporaryFolder()

    @Test
    fun theSlowestClusterIsNotCountedAsPerformanceCores() {
        val littleCores = List(4) { 1_800_000L }
        val bigCores = List(3) { 2_400_000L } + 2_840_000L

        assertEquals(4, DeviceInfo.threadCountFor(littleCores + bigCores, processors = 8))
    }

    @Test
    fun coresThatAllRunAtTheSameSpeedAllCount() {
        assertEquals(4, DeviceInfo.threadCountFor(List(4) { 2_000_000L }, processors = 4))
    }

    @Test
    fun theThreadCountIsAtMostEight() {
        val bigServerLikeChip = List(10) { 3_000_000L } + List(2) { 1_000_000L }

        assertEquals(8, DeviceInfo.threadCountFor(bigServerLikeChip, processors = 12))
    }

    @Test
    fun withoutFrequenciesEveryProcessorCountsUpToEight() {
        assertEquals(2, DeviceInfo.threadCountFor(emptyList(), processors = 2))
        assertEquals(8, DeviceInfo.threadCountFor(emptyList(), processors = 16))
    }

    @Test
    fun theThreadCountIsAtLeastOne() {
        assertEquals(1, DeviceInfo.threadCountFor(emptyList(), processors = 0))
    }

    @Test
    fun eachCoresMaximumFrequencyIsReadFromSysfsAndOtherFoldersAreSkipped() {
        core("cpu0", "1800000\n")
        core("cpu1", "1800000\n")
        core("cpu10", "2840000\n")
        core("cpufreq", "999")
        sysfs.newFolder("cpuidle")

        assertEquals(listOf(1_800_000L, 1_800_000L, 2_840_000L), DeviceInfo.maxFrequenciesKhz(sysfs.root).sorted())
    }

    @Test
    fun aCoreWithoutAReadableFrequencyIsLeftOut() {
        core("cpu0", "1800000")
        sysfs.newFolder("cpu1")
        core("cpu2", "not a number")

        assertEquals(listOf(1_800_000L), DeviceInfo.maxFrequenciesKhz(sysfs.root))
    }

    @Test
    fun aMissingCpuFolderGivesNoFrequencies() {
        assertEquals(emptyList<Long>(), DeviceInfo.maxFrequenciesKhz(File(sysfs.root, "absent")))
    }

    private fun core(name: String, maxFrequency: String) {
        val frequencyFile = File(sysfs.root, "$name/cpufreq/cpuinfo_max_freq")
        frequencyFile.parentFile?.mkdirs()
        frequencyFile.writeText(maxFrequency)
    }
}
