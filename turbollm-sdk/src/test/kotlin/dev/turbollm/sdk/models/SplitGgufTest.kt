package dev.turbollm.sdk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitGgufTest {

    @Test
    fun aShardNameGivesItsStemIndexAndTotal() {
        assertEquals(SplitShard(stem = "Big-Q8_0", index = 2, total = 3), splitShard("Big-Q8_0-00002-of-00003.gguf"))
    }

    @Test
    fun theExtensionIsMatchedCaseInsensitively() {
        assertEquals(SplitShard(stem = "Big", index = 1, total = 2), splitShard("Big-00001-of-00002.GGUF"))
    }

    @Test
    fun anUnsplitFileIsNotAShard() {
        assertNull(splitShard("Qwen3-1.7B-Q4_K_M.gguf"))
        assertNull(splitShard("Big-0001-of-0002.gguf"))
        assertNull(splitShard("Big-00001-of-00002.litertlm"))
    }

    @Test
    fun theFirstShardNameIsRebuiltFromTheStemAndTotal() {
        assertEquals("Big-00001-of-00012.gguf", SplitShard(stem = "Big", index = 7, total = 12).firstShardName())
    }
}
