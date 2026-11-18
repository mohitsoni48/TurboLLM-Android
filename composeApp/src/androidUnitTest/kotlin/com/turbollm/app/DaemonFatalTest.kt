package com.turbollm.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The record the daemon leaves when it exits 69 (plan P-13): T25b's app half. The record's file name carries the
 *  identity of the install that wrote it, so any other install, a same-version reinstall included, never sees it. */
class DaemonFatalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun recordJson(message: String = "no core", at: String? = "2026-11-15T10:00:00Z"): String =
        buildString {
            append("""{"code":"core_unavailable","exitCode":69,"message":"$message"""")
            if (at != null) append(""","at":"$at"""")
            append("}")
        }

    private fun record(versionCode: Long = 7, installedAt: Long = 1000) =
        DaemonFatalRecord(folder.root, versionCode, installedAt)

    private fun write(file: File, text: String = recordJson()) = file.also { it.writeText(text) }

    private fun daemonFatalFiles(): List<String> =
        folder.root.list().orEmpty().filter { it.startsWith("daemon-fatal") }.sorted()

    @Test
    fun theFileNameCarriesTheVersionCodeAndTheInstallTime() {
        assertEquals("daemon-fatal-7-1000.json", record(7, 1000).file.name)
        assertEquals(folder.root, record().file.parentFile)
        assertNotEquals(record(7, 1000).file, record(7, 2000).file)
        assertNotEquals(record(7, 1000).file, record(8, 1000).file)
    }

    @Test
    fun anAbsentFileIsNoRecord() {
        assertNull(record().read())
    }

    @Test
    fun aRecordUnderTheCurrentInstallsNameIsReturnedAndLeftAlone() {
        val current = record()
        val text = recordJson()
        write(current.file, text)

        assertEquals(DaemonFatal("core_unavailable", 69, "no core", "2026-11-15T10:00:00Z"), current.read())
        assertEquals(text, current.file.readText())
    }

    @Test
    fun aMissingAtReadsAsEmpty() {
        write(record().file, recordJson(at = null))

        assertEquals(DaemonFatal("core_unavailable", 69, "no core", ""), record().read())
    }

    @Test
    fun aSameVersionReinstallDoesNotSeeTheEarlierInstallsRecord() {
        write(record(7, 1000).file)
        val reinstalled = record(7, 2000)

        reinstalled.sweepStale()

        assertNull(reinstalled.read())
        assertEquals(emptyList<String>(), daemonFatalFiles())
    }

    @Test
    fun anUpdateBeforeTheNextLaunchDoesNotSeeTheOldVersionsRecord() {
        write(record(7, 1000).file)
        val updated = record(8, 3000)

        updated.sweepStale()

        assertNull(updated.read())
        assertEquals(emptyList<String>(), daemonFatalFiles())
    }

    @Test
    fun sweepingKeepsTheCurrentInstallsRecordAndRemovesEveryOther() {
        val current = record(7, 2000)
        write(current.file)
        write(record(7, 1000).file)
        write(record(6, 2000).file)
        write(File(folder.root, "daemon-fatal.json"))
        val unrelated = write(File(folder.root, "daemon.log"), "kept")

        current.sweepStale()

        assertEquals(listOf(current.file.name), daemonFatalFiles())
        assertTrue(unrelated.exists())
        assertEquals("core_unavailable", current.read()?.code)
    }

    @Test
    fun malformedJsonIsNoRecordAndTheFileIsDeleted() {
        val current = record()
        write(current.file, "{not json")

        assertNull(current.read())
        assertFalse(current.file.exists())
    }

    @Test
    fun aRecordMissingItsMessageIsNoRecordAndTheFileIsDeleted() {
        val current = record()
        write(current.file, """{"code":"core_unavailable","exitCode":69}""")

        assertNull(current.read())
        assertFalse(current.file.exists())
    }

    @Test
    fun clearingRemovesTheRecordAndToleratesAMissingOne() {
        val current = record()
        write(current.file)

        current.clear()
        assertFalse(current.file.exists())
        current.clear()
        assertFalse(current.file.exists())
    }
}
