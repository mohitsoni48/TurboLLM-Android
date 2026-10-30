package dev.turbollm.sdk.internal

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        LoadSentinel(modelRoot).write(model)

        val expected = buildJsonObject { put("path", JsonPrimitive(model.absolutePath)) }
        assertEquals(expected, sdkJson.parseToJsonElement(sentinelFile.readText()))
    }

    @Test
    fun clearingRemovesTheSentinel() {
        val sentinel = LoadSentinel(modelRoot)
        sentinel.write(File(modelRoot, "m.gguf"))

        sentinel.clear()

        assertFalse(sentinelFile.exists())
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
