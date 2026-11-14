package dev.turbollm.sdk.models

import dev.turbollm.sdk.InstalledModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.internal.Clock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("models") }
    private var now = 0L
    private var loadedId: String? = null
    private val store by lazy { ModelStore(root, Clock { now }, loadedModelId = { loadedId }) }

    @Test
    fun aGgufIsListedUnderItsReadableId() {
        val file = gguf("unsloth/Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M.gguf")

        val expected = InstalledModel(
            id = "unsloth/Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M",
            path = file.absolutePath,
            format = ModelFormat.GGUF,
            sizeBytes = ONE_MIB.toLong(),
            quant = "Q4_K_M",
            shards = 1,
            complete = true,
        )
        assertEquals(listOf(expected), store.list())
    }

    @Test
    fun aLiteRtBundleIsListedWithItsPrecision() {
        val file = litertlm("litert-community/Gemma3-1B-IT/gemma3-1b-it-int4.litertlm")

        val model = store.list().single()

        assertEquals("litert-community/Gemma3-1B-IT/gemma3-1b-it-int4", model.id)
        assertEquals(file.absolutePath, model.path)
        assertEquals(ModelFormat.LITERTLM, model.format)
        assertEquals("INT4", model.quant)
    }

    @Test
    fun aCompleteSplitGroupIsOneModelAddressedByItsFirstShard() {
        val first = gguf("org/repo/Big-Q8_0-00001-of-00003.gguf")
        gguf("org/repo/Big-Q8_0-00002-of-00003.gguf")
        gguf("org/repo/Big-Q8_0-00003-of-00003.gguf")

        val model = store.list().single()

        assertEquals("org/repo/Big-Q8_0", model.id)
        assertEquals(first.absolutePath, model.path)
        assertEquals(3, model.shards)
        assertEquals(3L * ONE_MIB, model.sizeBytes)
        assertEquals("Q8_0", model.quant)
        assertTrue(model.complete)
    }

    @Test
    fun aSplitGroupMissingAShardIsListedIncomplete() {
        gguf("org/repo/Big-00001-of-00003.gguf")
        gguf("org/repo/Big-00003-of-00003.gguf")

        val model = store.list().single()

        assertEquals("org/repo/Big", model.id)
        assertEquals(3, model.shards)
        assertFalse(model.complete)
    }

    @Test
    fun partialDownloadsAndImportsAreSkipped() {
        gguf("org/repo/a.gguf.part")
        gguf("org/repo/b.gguf.import-part")

        assertEquals(emptyList<InstalledModel>(), store.list())
    }

    @Test
    fun aFileWithTheWrongMagicIsSkipped() {
        write("org/repo/fake.gguf", "<html>".toByteArray(), ONE_MIB)
        write("org/repo/fake.litertlm", "NOTLITE!".toByteArray(), 64)

        assertEquals(emptyList<InstalledModel>(), store.list())
    }

    @Test
    fun aGgufSmallerThanOneMebibyteIsSkipped() {
        write("org/repo/tiny.gguf", GGUF_MAGIC, ONE_MIB - 1)

        assertEquals(emptyList<InstalledModel>(), store.list())
    }

    @Test
    fun aVisionProjectorIsSkipped() {
        gguf("org/repo/mmproj-model-f16.gguf")
        gguf("org/repo/Model-MMPROJ-F16.gguf")

        assertEquals(emptyList<InstalledModel>(), store.list())
    }

    @Test
    fun dotDirectoriesAreNotWalked() {
        gguf(".turbollm-sdk/hidden.gguf")
        gguf("org/.cache/hidden.gguf")

        assertEquals(emptyList<InstalledModel>(), store.list())
    }

    @Test
    fun theWalkStopsAtDepthEight() {
        gguf("1/2/3/4/5/6/7/deep.gguf")
        gguf("1/2/3/4/5/6/7/8/deeper.gguf")

        assertEquals(listOf("1/2/3/4/5/6/7/deep"), store.list().map { it.id })
    }

    @Test
    fun aFileWrittenByAnotherWriterAppearsOnceTheListingIsTwoSecondsOld() {
        store.list()
        gguf("org/repo/new.gguf")

        now = 1_999
        assertEquals(emptyList<InstalledModel>(), store.list())
        now = 2_000
        assertEquals(listOf("org/repo/new"), store.list().map { it.id })
    }

    @Test
    fun invalidateForcesARescanAtOnce() {
        store.list()
        gguf("org/repo/new.gguf")

        store.invalidate()

        assertEquals(listOf("org/repo/new"), store.list().map { it.id })
    }

    @Test
    fun deleteRemovesEveryShardOfTheModel() {
        val shards = (1..2).map { gguf("org/repo/Big-0000$it-of-00002.gguf") }
        val other = gguf("org/repo/other.gguf")

        store.delete("org/repo/Big")

        assertTrue(shards.none { it.exists() })
        assertTrue(other.exists())
        assertEquals(listOf("org/repo/other"), store.list().map { it.id })
    }

    @Test
    fun deleteRefusesTheLoadedModel() {
        val file = gguf("org/repo/a.gguf")
        loadedId = "org/repo/a"

        assertThrows(IllegalStateException::class.java) { store.delete("org/repo/a") }
        assertTrue(file.exists())
    }

    @Test
    fun deletingAnUnknownModelIsModelNotFound() {
        assertThrows(TurboLLMException.ModelNotFound::class.java) { store.delete("org/repo/nope") }
    }

    private fun gguf(relative: String) = write(relative, GGUF_MAGIC, ONE_MIB)

    private fun litertlm(relative: String) = write(relative, LITERTLM_MAGIC, 64)

    private fun write(relative: String, magic: ByteArray, size: Int): File {
        val file = File(root, relative)
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { out ->
            out.write(magic)
            out.write(ByteArray(size - magic.size))
        }
        return file
    }

    private companion object {
        const val ONE_MIB = 1024 * 1024
        val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)
        val LITERTLM_MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)
    }
}
