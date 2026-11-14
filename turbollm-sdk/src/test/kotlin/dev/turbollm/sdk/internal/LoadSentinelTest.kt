package dev.turbollm.sdk.internal

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadSentinelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val modelRoot: File by lazy { temp.newFolder("models") }
    private val sentinelFile: File by lazy { File(modelRoot, ".turbollm-sdk/loading.json") }

    @Test
    fun aWrittenSentinelNamesTheModelsAbsolutePath() {
        val model = File(modelRoot, "org/repo/m.gguf")

        LoadSentinel(modelRoot, owner = "core-a").write(model)

        val expected = buildJsonObject {
            put("path", JsonPrimitive(model.absolutePath))
            put("owner", JsonPrimitive("core-a"))
        }
        assertEquals(expected, sdkJson.parseToJsonElement(sentinelFile.readText()))
    }

    @Test
    fun aSentinelThatCannotBeWrittenIsReportedAsNotWritten() {
        File(modelRoot, ".turbollm-sdk").writeText("a file where the sentinel folder belongs")

        assertFalse(LoadSentinel(modelRoot).write(File(modelRoot, "m.gguf")))
    }

    @Test
    fun aWrittenSentinelIsReportedAsWritten() {
        assertTrue(LoadSentinel(modelRoot).write(File(modelRoot, "m.gguf")))
    }

    @Test
    fun clearingRemovesTheSentinel() {
        val sentinel = LoadSentinel(modelRoot)
        sentinel.write(File(modelRoot, "m.gguf"))

        sentinel.clear()

        assertFalse(sentinelFile.exists())
    }

    @Test
    fun aCoreNeverClearsTheSentinelAnotherCoreWrote() {
        val earlier = LoadSentinel(modelRoot, owner = "core-a")
        val later = LoadSentinel(modelRoot, owner = "core-b")
        val laterModel = File(modelRoot, "y.gguf")
        earlier.write(File(modelRoot, "x.gguf"))
        later.write(laterModel)

        earlier.clear()

        assertEquals(laterModel.absolutePath, LoadSentinel(modelRoot).consumeStale())
    }

    @Test
    fun aSentinelLeftByACrashedLoadIsReturnedOnceThenNull() {
        val model = File(modelRoot, "m.gguf")
        LoadSentinel(modelRoot).write(model)

        val afterRestart = LoadSentinel(modelRoot)

        assertEquals(model.absolutePath, afterRestart.consumeStale())
        assertNull(afterRestart.consumeStale())
        assertFalse(sentinelFile.exists())
    }

    @Test
    fun withoutALeftoverSentinelThereIsNothingStale() {
        assertNull(LoadSentinel(modelRoot).consumeStale())
    }

    @Test
    fun anUnreadableLeftoverIsDiscardedAsNothingStale() {
        File(modelRoot, ".turbollm-sdk").mkdirs()
        sentinelFile.writeText("{not json")

        assertNull(LoadSentinel(modelRoot).consumeStale())
        assertFalse(sentinelFile.exists())
    }
}
