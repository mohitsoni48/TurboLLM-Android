package dev.turbollm.sdk.internal

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {

    @Test
    fun chatCompletionIdIsPrefixedWith24LowercaseHexDigits() {
        val id = chatCompletionId()

        assertTrue("unexpected id shape: $id", Regex("^chatcmpl-[0-9a-f]{24}$").matches(id))
    }

    @Test
    fun everyChatCompletionIdIsFresh() {
        assertNotEquals(chatCompletionId(), chatCompletionId())
    }
}
