package dev.turbollm.sdk.internal

import dev.turbollm.sdk.EngineId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugFaultsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parseReadsEachDirectiveAndSkipsCommentsBlankLinesAndUnknownLines() {
        val faults = DebugFaults.parse("unavailable=litert-lm\nno-server\n# x\n\nbogus")

        assertEquals(DebugFaults(unavailable = setOf(EngineId.LITERT_LM), noServer = true), faults)
    }

    @Test
    fun parseCollectsEveryEngineMadeUnavailable() {
        val faults = DebugFaults.parse("unavailable=litert-lm\r\n  unavailable=llama.cpp-cpu  \n")

        assertEquals(setOf(EngineId.LITERT_LM, EngineId.LLAMA_CPP_CPU), faults.unavailable)
        assertEquals(false, faults.noServer)
    }

    @Test
    fun aNonDebuggableAppNeverReadsTheFile() {
        val dir = faultsDir("unavailable=litert-lm\nno-server\n")

        assertEquals(DebugFaults.NONE, DebugFaults.read(dir, debuggable = false))
    }

    @Test
    fun aDebuggableAppReadsTheFile() {
        val dir = faultsDir("no-server\n")

        assertEquals(DebugFaults(unavailable = emptySet(), noServer = true), DebugFaults.read(dir, debuggable = true))
    }

    @Test
    fun aMissingFileMeansNoFaults() {
        assertEquals(DebugFaults.NONE, DebugFaults.read(temp.newFolder("empty"), debuggable = true))
    }

    @Test
    fun anUnreadableFaultsPathMeansNoFaultsAndNeverThrows() {
        val dir = temp.newFolder("turbollm-sdk-debug")
        File(dir, FAULTS_FILE).mkdir()

        assertEquals(DebugFaults.NONE, DebugFaults.read(dir, debuggable = true))
    }

    @Test
    fun aDebugFolderThatIsAFileMeansNoFaults() {
        val notAFolder = temp.newFile("turbollm-sdk-debug")

        assertEquals(DebugFaults.NONE, DebugFaults.read(notAFolder, debuggable = true))
    }

    @Test
    fun aFaultedEngineProbeReportsTheDebugReasonAndAnotherKeepsItsRealProbe() {
        val faults = DebugFaults(unavailable = setOf(EngineId.LITERT_LM), noServer = false)

        assertEquals("Disabled for testing (debug build).", faults.probeFor(EngineId.LITERT_LM) { null }())
        assertEquals("real reason", faults.probeFor(EngineId.LLAMA_CPP_CPU) { "real reason" }())
    }

    private fun faultsDir(text: String): File {
        val dir = temp.newFolder("turbollm-sdk-debug")
        File(dir, FAULTS_FILE).writeText(text)
        return dir
    }

    private companion object {
        const val FAULTS_FILE = "faults"
    }
}
