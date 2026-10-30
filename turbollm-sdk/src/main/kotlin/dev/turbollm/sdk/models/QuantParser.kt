package dev.turbollm.sdk.models

import dev.turbollm.sdk.ModelFormat
import java.util.Locale

/** llama.cpp quant names (`turbollm/src/gguf/gguf.ts` `quantFromName`). */
private val GGUF_QUANT = Regex(
    """(?:^|[-_. ])(I?Q\d(?:_[A-Za-z0-9]+)*|BF16|F16|F32)(?=$|[-_. ])""",
    RegexOption.IGNORE_CASE,
)

/** litert-community precision names (`turbollm/src/models/litertlm.ts` `QUANT_RE`). */
private val LITERTLM_QUANT = Regex(
    """(?:^|[-_. ])(int[248]|fp16|f16|fp32|bf16|q[48])(?=$|[-_. ])""",
    RegexOption.IGNORE_CASE,
)

/**
 * The quant a file name states, upper-cased; null when it states none. Anchored on separators, and the last
 * match wins, because a model name can contain a number-letter run of its own.
 */
internal fun quantFromName(fileName: String, format: ModelFormat): String? {
    val pattern = when (format) {
        ModelFormat.GGUF -> GGUF_QUANT
        ModelFormat.LITERTLM -> LITERTLM_QUANT
    }
    return pattern.findAll(fileName).lastOrNull()?.groupValues?.get(1)?.uppercase(Locale.ROOT)
}
