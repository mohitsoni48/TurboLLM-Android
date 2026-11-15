package com.turbollm.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The record the daemon leaves when it exits 69 (plan P-13): T25b's app half. */
class DaemonFatalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val record = """{"code":"core_unavailable","exitCode":69,"message":"no core","at":"2026-11-15T10:00:00Z"}"""

    private fun fatalFile(text: String? = null): File =
        File(folder.root, "daemon-fatal.json").also { if (text != null) it.writeText(text) }

    private fun versionCodeOf(file: File): Long =
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("versionCode").jsonPrimitive.long

    @Test
    fun anAbsentFileIsNoRecord() {
        assertNull(readDaemonFatal(fatalFile(), 7))
    }

    @Test
    fun aRecordOfTheSameVersionIsReturned() {
        val file = fatalFile("""{"code":"core_unavailable","exitCode":69,"message":"no core","at":"t","versionCode":7}""")

        assertEquals(DaemonFatal("core_unavailable", 69, "no core", "t"), readDaemonFatal(file, 7))
        assertTrue(file.exists())
    }

    @Test
    fun aRecordWithoutAVersionCodeIsReturnedAndStampedWithTheCurrentOne() {
        val file = fatalFile(record)

        assertEquals(DaemonFatal("core_unavailable", 69, "no core", "2026-11-15T10:00:00Z"), readDaemonFatal(file, 7))
        assertEquals(7L, versionCodeOf(file))
        val stamped = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(69, stamped.getValue("exitCode").jsonPrimitive.int)
        assertEquals("no core", stamped.getValue("message").jsonPrimitive.content)
        // Read again under the same version: still the record.
        assertNotNull(readDaemonFatal(file, 7))
    }

    @Test
    fun aRecordOfAnotherVersionIsNoRecordAndTheFileIsDeleted() {
        val file = fatalFile("""{"code":"core_unavailable","exitCode":69,"message":"m","at":"t","versionCode":6}""")

        assertNull(readDaemonFatal(file, 7))
        assertFalse(file.exists())
    }

    @Test
    fun aStampedRecordIsDroppedOnceTheAppIsUpdated() {
        val file = fatalFile(record)
        assertNotNull(readDaemonFatal(file, 7))

        assertNull(readDaemonFatal(file, 8))
        assertFalse(file.exists())
    }

    @Test
    fun malformedJsonIsNoRecordAndTheFileIsDeleted() {
        val file = fatalFile("{not json")

        assertNull(readDaemonFatal(file, 7))
        assertFalse(file.exists())
    }

    @Test
    fun aRecordMissingItsMessageIsNoRecordAndTheFileIsDeleted() {
        val file = fatalFile("""{"code":"core_unavailable","exitCode":69}""")

        assertNull(readDaemonFatal(file, 7))
        assertFalse(file.exists())
    }

    @Test
    fun clearingRemovesTheRecordAndToleratesAMissingOne() {
        val file = fatalFile(record)

        clearDaemonFatal(file)
        assertFalse(file.exists())
        clearDaemonFatal(file)
    }
}
