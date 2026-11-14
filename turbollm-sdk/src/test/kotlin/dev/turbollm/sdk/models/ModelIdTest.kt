package dev.turbollm.sdk.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelIdTest {

    private val root = File("models-root")

    @Test
    fun theIdIsTheRelativePathWithoutTheExtension() {
        val file = File(root, "unsloth/Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M.gguf")

        assertEquals("unsloth/Qwen3-1.7B-GGUF/Qwen3-1.7B-Q4_K_M", modelId(root, file))
    }

    @Test
    fun theIdUsesForwardSlashesOnEveryPlatform() {
        val file = File(File(File(root, "org"), "repo"), "m.litertlm")

        assertEquals("org/repo/m", modelId(root, file))
    }

    @Test
    fun aSplitShardsIdDropsTheShardSuffix() {
        val file = File(root, "org/repo/Big-Q8_0-00002-of-00003.gguf")

        assertEquals("org/repo/Big-Q8_0", modelId(root, file))
    }

    @Test
    fun aFileAtTheRootHasABareId() {
        assertEquals("solo", modelId(root, File(root, "solo.gguf")))
    }
}
