package dev.turbollm.sdk.models

import dev.turbollm.sdk.ModelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantParserTest {

    @Test
    fun ggufQuantsAreReadFromTheFileName() {
        val cases = mapOf(
            "Qwen3-1.7B-Q4_K_M.gguf" to "Q4_K_M",
            "model.IQ3_XXS.gguf" to "IQ3_XXS",
            "llama-3-8b-q8_0.gguf" to "Q8_0",
            "phi-F16.gguf" to "F16",
            "gemma_bf16.gguf" to "BF16",
            "Big-Q8_0-00001-of-00003.gguf" to "Q8_0",
        )

        cases.forEach { (name, quant) -> assertEquals(name, quant, quantFromName(name, ModelFormat.GGUF)) }
    }

    @Test
    fun theLastGgufQuantInTheNameWins() {
        assertEquals("IQ4_XS", quantFromName("Q8-distill-IQ4_XS.gguf", ModelFormat.GGUF))
    }

    @Test
    fun aQuantGluedToOtherTextIsNotOne() {
        assertNull(quantFromName("Qwen3Q4model.gguf", ModelFormat.GGUF))
    }

    @Test
    fun aNameWithoutAQuantHasNone() {
        assertNull(quantFromName("my-model.gguf", ModelFormat.GGUF))
        assertNull(quantFromName("gemma3-1b-it.litertlm", ModelFormat.LITERTLM))
    }

    @Test
    fun liteRtPrecisionsAreReadFromTheFileName() {
        val cases = mapOf(
            "gemma3-1b-it-int4.litertlm" to "INT4",
            "Qwen2.5-1.5B_q8_ekv4096.litertlm" to "Q8",
            "gemma-3n-E2B-it-fp16.litertlm" to "FP16",
            "model-int8-fp32.litertlm" to "FP32",
        )

        cases.forEach { (name, quant) -> assertEquals(name, quant, quantFromName(name, ModelFormat.LITERTLM)) }
    }
}
